package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.endsWith;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认令牌单测：短期授权、处理租约、归属/过期校验与故障语义。
 */
@ExtendWith(MockitoExtension.class)
class ConfirmationServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> tokenBucket;
    @Mock
    private RBucket<String> claimBucket;

    private final ObjectMapper objectMapper = JsonUtil.copyMapper();
    private ConfirmationService service;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getTool().getConfirmation().setTtlSeconds(120);
        properties.getTool().getConfirmation().setClaimSeconds(600);
        service = new ConfirmationService(redissonClient, properties);
        // 默认返回令牌 bucket；更具体的 :claim 后缀 stub 放在后面，优先命中
        lenient().doReturn(tokenBucket).when(redissonClient).getBucket(anyString());
        lenient().doReturn(claimBucket).when(redissonClient).getBucket(endsWith(":claim"));
    }

    private ConfirmationService.PendingAction action(String userId) {
        return new ConfirmationService.PendingAction(
                "cfm_1", userId, "c1", "model-x", "complete_demo_record",
                List.of(Map.of("tool", "complete_demo_record", "args", Map.of("recordId", 1))),
                "即将执行：完成演示记录。", "WRITE",
                System.currentTimeMillis() + 60_000);
    }

    @Test
    @DisplayName("创建确认令牌 → 写入 Redis 并返回带过期时间的动作")
    void createWritesTokenWithTtl() throws Exception {
        ConfirmationService.PendingAction created = service.create(
                "demo", "c1", "model-x", "complete_demo_record",
                List.of(Map.of("tool", "complete_demo_record", "args", Map.of("recordId", 1))),
                "即将执行：完成演示记录。", "WRITE");

        assertThat(created.confirmationId()).startsWith("cfm_");
        assertThat(created.expiresAt()).isGreaterThan(System.currentTimeMillis());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(tokenBucket).set(jsonCaptor.capture(), eq(120L), eq(TimeUnit.SECONDS));
        ConfirmationService.PendingAction stored = objectMapper.readValue(
                jsonCaptor.getValue(), ConfirmationService.PendingAction.class);
        assertThat(stored.confirmationId()).isEqualTo(created.confirmationId());
        assertThat(stored.userId()).isEqualTo("demo");
        assertThat(stored.plan()).hasSize(1);
    }

    @Test
    @DisplayName("preview 只读：不抢租约、不删令牌")
    void previewDoesNotClaim() throws Exception {
        when(tokenBucket.get()).thenReturn(objectMapper.writeValueAsString(action("demo")));

        Optional<ConfirmationService.PendingAction> preview = service.preview("cfm_1", "demo");

        assertThat(preview).isPresent();
        verify(claimBucket, never()).trySet(anyString(), anyLong(), any());
        verify(tokenBucket, never()).delete();
    }

    @Test
    @DisplayName("同一令牌只能抢占一次：第二个请求收到执行中")
    void claimIsExclusive() throws Exception {
        when(tokenBucket.get()).thenReturn(objectMapper.writeValueAsString(action("demo")));
        when(claimBucket.trySet("1", 600L, TimeUnit.SECONDS)).thenReturn(true, false);

        ConfirmationService.PendingAction claimed = service.claim("cfm_1", "demo");

        assertThat(claimed.confirmationId()).isEqualTo("cfm_1");
        assertThatThrownBy(() -> service.claim("cfm_1", "demo"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CONFIRMATION_IN_PROGRESS.getCode());
    }

    @Test
    @DisplayName("他人令牌 → 视为不存在，且不抢占、不删除")
    void rejectOtherUsersToken() throws Exception {
        when(tokenBucket.get()).thenReturn(objectMapper.writeValueAsString(action("owner")));

        assertThatThrownBy(() -> service.claim("cfm_1", "attacker"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
        verify(claimBucket, never()).trySet(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("令牌已过期 → 拒绝执行且不抢占")
    void rejectExpiredToken() throws Exception {
        ConfirmationService.PendingAction expired = new ConfirmationService.PendingAction(
                "cfm_1", "demo", "c1", "model-x", "complete_demo_record",
                List.of(), "已过期", "WRITE", System.currentTimeMillis() - 1);
        when(tokenBucket.get()).thenReturn(objectMapper.writeValueAsString(expired));

        assertThatThrownBy(() -> service.claim("cfm_1", "demo"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
        verify(claimBucket, never()).trySet(anyString(), anyLong(), any());
    }

    @Test
    @DisplayName("Redis 读取失败 → 503 而不是伪装成 404")
    void previewFailsClosedWithServiceUnavailable() {
        when(tokenBucket.get()).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> service.preview("cfm_1", "demo"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE.getCode());
    }

    @Test
    @DisplayName("release 只释放租约，保留令牌供重试")
    void releaseKeepsToken() {
        service.release("cfm_1");

        verify(claimBucket).delete();
        verify(tokenBucket, never()).delete();
    }

    @Test
    @DisplayName("finish 同时删除租约与令牌")
    void finishDeletesTokenAndClaim() {
        service.finish("cfm_1");

        verify(claimBucket).delete();
        verify(tokenBucket).delete();
    }

    @Test
    @DisplayName("Redis 创建异常 → fail-closed，抛出缓存不可用")
    void createFailsClosedOnRedisError() {
        doThrow(new RuntimeException("redis down"))
                .when(tokenBucket).set(anyString(), anyLong(), eq(TimeUnit.SECONDS));

        assertThatThrownBy(() -> service.create(
                "demo", "c1", "model-x", "complete_demo_record", List.of(),
                "即将执行：完成演示记录。", "WRITE"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE.getCode());
    }
}
