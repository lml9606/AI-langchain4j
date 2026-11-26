package com.example.langchain4j.tool.confirmation;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.tool.execution.ToolExecutionService;
import com.example.langchain4j.tool.run.TaskRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 确认令牌测评：验证单会话单动作、台账失败回滚和 Redis 故障 fail-closed。 */
@ExtendWith(MockitoExtension.class)
class ConfirmationServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> bucket;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private TaskRunService taskRunService;

    private ConfirmationService service;

    /** 构造固定 TTL 的确认服务，并把所有会话桶指向同一个 mock。 */
    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getTool().getConfirmation().setTtlSeconds(120);
        service = new ConfirmationService(
                redissonClient, properties, toolExecutionService, taskRunService);
        doReturn(bucket).when(redissonClient).getBucket(anyString());
    }

    /** 创建成功时先写 Redis，再把同一批步骤写入数据库台账。 */
    @Test
    void createStoresTokenAndPendingLedger() throws Exception {
        when(bucket.trySet(anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);

        ConfirmationService.PendingAction action = service.create(
                "alice", "c1", "model-x",
                List.of(new ConfirmationService.PendingAction.Step(
                        1, "complete_demo_record", Map.of("recordId", "R1"), "完成记录")),
                "WRITE");

        assertThat(action.confirmationId()).startsWith("cfm_");
        assertThat(action.userId()).isEqualTo("alice");
        assertThat(action.steps()).hasSize(1);
        ArgumentCaptor<String> json = ArgumentCaptor.forClass(String.class);
        verify(bucket).trySet(json.capture(), eq(120L), eq(TimeUnit.SECONDS));
        assertThat(JsonUtil.fromJson(json.getValue(),
                ConfirmationService.PendingAction.class).confirmationId())
                .isEqualTo(action.confirmationId());
        verify(taskRunService).createPending(any(), any());
    }

    /** 同一会话已有待确认动作时拒绝创建第二张令牌。 */
    @Test
    void duplicatePendingActionIsRejected() {
        when(bucket.trySet(anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                "alice", "c1", "model-x", "complete_demo_record",
                Map.of("recordId", "R1"), "完成记录", "WRITE"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.CONFIRMATION_PENDING.getCode());
        verify(taskRunService, never()).createPending(any(), any());
    }

    /** 数据库台账写入失败时必须删除刚创建的 Redis 令牌，不能下发悬空确认卡片。 */
    @Test
    void databaseFailureRollsBackRedisToken() {
        when(bucket.trySet(anyString(), anyLong(), eq(TimeUnit.SECONDS))).thenReturn(true);
        doThrow(new IllegalStateException("db down"))
                .when(taskRunService).createPending(any(), any());

        assertThatThrownBy(() -> service.create(
                "alice", "c1", "model-x", "complete_demo_record",
                Map.of("recordId", "R1"), "完成记录", "WRITE"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.DB_UNAVAILABLE.getCode());
        verify(bucket).delete();
    }

    /** 读取令牌时 Redis 异常必须返回服务不可用，不能当作令牌不存在继续。 */
    @Test
    void previewFailsClosedWhenRedisUnavailable() {
        when(bucket.get()).thenThrow(new IllegalStateException("redis down"));

        assertThatThrownBy(() -> service.preview("c1", "cfm_1", "alice"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE.getCode());
    }

    /** 其他用户即使拿到确认 ID 也不能预览或执行令牌。 */
    @Test
    void previewRejectsDifferentUser() throws Exception {
        ConfirmationService.PendingAction action = action();
        when(bucket.get()).thenReturn(JsonUtil.toJson(action));

        assertThat(service.preview("c1", action.confirmationId(), "attacker")).isEmpty();
    }

    /** 会话、确认 ID、用户三者一致时才能读到可执行动作。 */
    @Test
    void previewReturnsMatchingAction() throws Exception {
        ConfirmationService.PendingAction action = action();
        when(bucket.get()).thenReturn(JsonUtil.toJson(action));

        Optional<ConfirmationService.PendingAction> result =
                service.preview("c1", action.confirmationId(), "alice");

        assertThat(result).contains(action);
    }

    /** 构造一条固定确认动作，供读取和归属测试复用。 */
    private ConfirmationService.PendingAction action() {
        return new ConfirmationService.PendingAction(
                "cfm_1", "alice", "c1", "model-x",
                "complete_demo_record", Map.of("recordId", "R1"),
                "完成记录", "WRITE", System.currentTimeMillis() + 60_000,
                List.of(new ConfirmationService.PendingAction.Step(
                        1, "complete_demo_record", Map.of("recordId", "R1"), "完成记录")));
    }
}
