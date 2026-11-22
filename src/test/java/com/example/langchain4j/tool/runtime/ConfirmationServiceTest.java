package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.service.ToolExecutionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认令牌单测：一个会话一个 key，key 的值就是当前待确认动作。
 */
@ExtendWith(MockitoExtension.class)
class ConfirmationServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> conversationBucket;
    @Mock
    private ToolExecutionService toolExecutionService;

    private final ObjectMapper objectMapper = JsonUtil.copyMapper();
    private ConfirmationService service;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getTool().getConfirmation().setTtlSeconds(120);
        service = new ConfirmationService(redissonClient, properties, toolExecutionService);
        lenient().doReturn(conversationBucket).when(redissonClient).getBucket(anyString());
        lenient().when(conversationBucket.trySet(anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(true);
        lenient().when(toolExecutionService.recordPending(any(ToolExecution.class)))
                .thenReturn(true);
    }

    private ConfirmationService.PendingAction action(String userId) {
        return new ConfirmationService.PendingAction(
                "cfm_1", userId, "c1", "model-x", "complete_demo_record",
                List.of(Map.of("tool", "complete_demo_record", "args", Map.of("recordId", 1))),
                "即将执行：完成演示记录。", "WRITE",
                System.currentTimeMillis() + 60_000);
    }

    @Test
    @DisplayName("创建待确认动作 → 只写会话 key，值就是待确认动作本身")
    void createWritesConversationKeyWithTtl() throws Exception {
        ConfirmationService.PendingAction created = service.create(
                "demo", "c1", "model-x", "complete_demo_record",
                List.of(Map.of("tool", "complete_demo_record", "args", Map.of("recordId", 1))),
                "即将执行：完成演示记录。", "WRITE");

        assertThat(created.confirmationId()).startsWith("cfm_");
        assertThat(created.expiresAt()).isGreaterThan(System.currentTimeMillis());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(conversationBucket).trySet(jsonCaptor.capture(), eq(120L), eq(TimeUnit.SECONDS));
        ConfirmationService.PendingAction stored = objectMapper.readValue(
                jsonCaptor.getValue(), ConfirmationService.PendingAction.class);
        assertThat(stored.confirmationId()).isEqualTo(created.confirmationId());
        assertThat(stored.userId()).isEqualTo("demo");
        assertThat(stored.conversationId()).isEqualTo("c1");
        assertThat(stored.plan()).hasSize(1);

        ArgumentCaptor<ToolExecution> executionCaptor = ArgumentCaptor.forClass(ToolExecution.class);
        verify(toolExecutionService).recordPending(executionCaptor.capture());
        ToolExecution execution = executionCaptor.getValue();
        assertThat(execution.getExecutionId()).isEqualTo(created.confirmationId());
        assertThat(execution.getUserId()).isEqualTo("demo");
        assertThat(execution.getConversationId()).isEqualTo("c1");
        assertThat(execution.getTool()).isEqualTo("complete_demo_record");
        assertThat(execution.getPlanJson()).contains("recordId");
        assertThat(execution.getSummary()).isEqualTo("即将执行：完成演示记录。");
        assertThat(execution.getRisk()).isEqualTo("WRITE");
        assertThat(execution.getExpiresAt()).isNotNull();
    }

    @Test
    @DisplayName("preview 只读：不删除会话 key")
    void previewDoesNotDeletePending() throws Exception {
        when(conversationBucket.get()).thenReturn(objectMapper.writeValueAsString(action("demo")));

        Optional<ConfirmationService.PendingAction> preview =
                service.preview("c1", "cfm_1", "demo");

        assertThat(preview).isPresent();
        verify(conversationBucket, never()).delete();
    }

    @Test
    @DisplayName("同一会话已有待确认动作 → trySet 失败，拒绝第二张令牌")
    void createRejectsSecondPendingAction() {
        when(conversationBucket.trySet(anyString(), anyLong(), eq(TimeUnit.SECONDS)))
                .thenReturn(false);

        assertThatThrownBy(() -> service.create(
                "demo", "c1", "model-x", "complete_demo_record", List.of(),
                "即将执行：完成演示记录。", "WRITE"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CONFIRMATION_PENDING.getCode());
        verify(toolExecutionService, never()).recordPending(any(ToolExecution.class));
    }

    @Test
    @DisplayName("confirmationId 不是会话当前动作 → 不能确认")
    void previewRejectsStaleConfirmationId() throws Exception {
        when(conversationBucket.get()).thenReturn(objectMapper.writeValueAsString(action("demo")));

        assertThat(service.preview("c1", "cfm_old", "demo")).isEmpty();
        verify(conversationBucket, never()).delete();
    }

    @Test
    @DisplayName("他人动作 → 视为不存在，且不删除")
    void rejectOtherUsersAction() throws Exception {
        when(conversationBucket.get()).thenReturn(objectMapper.writeValueAsString(action("owner")));

        assertThat(service.preview("c1", "cfm_1", "attacker")).isEmpty();
        verify(conversationBucket, never()).delete();
    }

    @Test
    @DisplayName("会话 key 不存在（含 TTL 到期）→ 视为不存在")
    void rejectMissingConversationKey() {
        when(conversationBucket.get()).thenReturn(null);

        assertThat(service.preview("c1", "cfm_1", "demo")).isEmpty();
        verify(conversationBucket, never()).delete();
    }

    @Test
    @DisplayName("Redis 读取失败 → 503 而不是伪装成 404")
    void previewFailsClosedWithServiceUnavailable() {
        when(conversationBucket.get()).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> service.preview("c1", "cfm_1", "demo"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE.getCode());
    }

    @Test
    @DisplayName("finish 删除会话 key")
    void finishDeletesConversationKey() throws Exception {
        when(conversationBucket.get()).thenReturn(objectMapper.writeValueAsString(action("demo")));

        service.finish("c1", "cfm_1");

        verify(conversationBucket).delete();
    }

    @Test
    @DisplayName("finish 不清除后续新动作")
    void finishDoesNotDeleteNewerAction() throws Exception {
        when(conversationBucket.get()).thenReturn(objectMapper.writeValueAsString(action("demo")));

        service.finish("c1", "cfm_old");

        verify(conversationBucket, never()).delete();
    }

    @Test
    @DisplayName("finish 遇到 Redis 故障只降级，不影响业务结果")
    void finishIgnoresRedisFailure() {
        when(conversationBucket.get()).thenThrow(new RuntimeException("redis down"));

        assertThatCode(() -> service.finish("c1", "cfm_1")).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("新消息取消待确认：删除会话令牌并同步取消台账")
    void cancelPendingDeletesTokenAndLedger() throws Exception {
        when(conversationBucket.get()).thenReturn(objectMapper.writeValueAsString(action("demo")));
        when(toolExecutionService.cancelPendingByConversation("demo", "c1")).thenReturn(1);

        Optional<ConfirmationService.PendingAction> cancelled =
                service.cancelPending("c1", "demo", "USER_NEW_MESSAGE");

        assertThat(cancelled).isPresent();
        assertThat(cancelled.get().confirmationId()).isEqualTo("cfm_1");
        verify(conversationBucket).delete();
        verify(toolExecutionService).cancelPendingByConversation("demo", "c1");
    }

    @Test
    @DisplayName("新消息取消时 Redis 故障：仍按数据库兜底取消，不阻断新意图")
    void cancelPendingFallsBackToLedgerWhenRedisFails() {
        when(conversationBucket.get()).thenThrow(new RuntimeException("redis down"));
        when(toolExecutionService.cancelPendingByConversation("demo", "c1")).thenReturn(1);

        assertThatCode(() -> service.cancelPending("c1", "demo", "USER_NEW_MESSAGE"))
                .doesNotThrowAnyException();

        verify(toolExecutionService).cancelPendingByConversation("demo", "c1");
    }

    @Test
    @DisplayName("Redis 创建异常 → fail-closed，抛出缓存不可用")
    void createFailsClosedOnRedisError() {
        doThrow(new RuntimeException("redis down"))
                .when(conversationBucket).trySet(anyString(), anyLong(), eq(TimeUnit.SECONDS));

        assertThatThrownBy(() -> service.create(
                "demo", "c1", "model-x", "complete_demo_record", List.of(),
                "即将执行：完成演示记录。", "WRITE"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE.getCode());
        verify(toolExecutionService, never()).recordPending(any(ToolExecution.class));
    }

    @Test
    @DisplayName("台账写入失败 → 撤销 Redis key，不下发无记录的确认卡片")
    void createRollsBackConversationKeyWhenPendingRecordRejected() {
        when(toolExecutionService.recordPending(any(ToolExecution.class))).thenReturn(false);

        assertThatThrownBy(() -> service.create(
                "demo", "c1", "model-x", "complete_demo_record", List.of(),
                "即将执行：完成演示记录。", "WRITE"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.INTERNAL_ERROR.getCode());

        verify(conversationBucket).delete();
    }

    @Test
    @DisplayName("台账写入异常 → 撤销 Redis key，抛出数据库不可用")
    void createRollsBackConversationKeyWhenPendingRecordFails() {
        when(toolExecutionService.recordPending(any(ToolExecution.class)))
                .thenThrow(new RuntimeException("db down"));

        assertThatThrownBy(() -> service.create(
                "demo", "c1", "model-x", "complete_demo_record", List.of(),
                "即将执行：完成演示记录。", "WRITE"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.DB_UNAVAILABLE.getCode());

        verify(conversationBucket).delete();
    }
}
