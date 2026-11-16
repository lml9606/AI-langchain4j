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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 确认令牌单测：创建落 Redis、一次性消费、归属/过期校验与故障 fail-closed。
 */
@ExtendWith(MockitoExtension.class)
class ConfirmationServiceTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RBucket<String> bucket;

    private final ObjectMapper objectMapper = JsonUtil.copyMapper();
    private ConfirmationService service;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getTool().getConfirmation().setTtlSeconds(120);
        service = new ConfirmationService(redissonClient, properties);
        lenient().doReturn(bucket).when(redissonClient).getBucket(anyString());
    }

    private ConfirmationService.PendingAction action(String userId) {
        return new ConfirmationService.PendingAction(
                "cfm_1", userId, "c1", "model-x", "cancel_order",
                List.of(Map.of("tool", "cancel_order", "args", Map.of("orderId", "A1001"))),
                "即将执行：取消订单。", "WRITE",
                System.currentTimeMillis() + 60_000);
    }

    @Test
    @DisplayName("创建确认令牌 → 写入 Redis 并返回带过期时间的动作")
    void createWritesTokenWithTtl() throws Exception {
        ConfirmationService.PendingAction created = service.create(
                "demo", "c1", "model-x", "cancel_order",
                List.of(Map.of("tool", "cancel_order", "args", Map.of("orderId", "A1001"))),
                "即将执行：取消订单。", "WRITE");

        assertThat(created.confirmationId()).startsWith("cfm_");
        assertThat(created.expiresAt()).isGreaterThan(System.currentTimeMillis());

        ArgumentCaptor<String> jsonCaptor = ArgumentCaptor.forClass(String.class);
        verify(bucket).set(jsonCaptor.capture(), eq(120L), eq(TimeUnit.SECONDS));
        ConfirmationService.PendingAction stored = objectMapper.readValue(
                jsonCaptor.getValue(), ConfirmationService.PendingAction.class);
        assertThat(stored.confirmationId()).isEqualTo(created.confirmationId());
        assertThat(stored.userId()).isEqualTo("demo");
        assertThat(stored.plan()).hasSize(1);
    }

    @Test
    @DisplayName("同一令牌第一次可消费，第二次已删除 → empty（一次性语义）")
    void consumeIsOneShot() throws Exception {
        when(bucket.getAndDelete())
                .thenReturn(objectMapper.writeValueAsString(action("demo")))
                .thenReturn(null);

        Optional<ConfirmationService.PendingAction> first = service.consume("cfm_1", "demo");
        Optional<ConfirmationService.PendingAction> second = service.consume("cfm_1", "demo");

        assertThat(first).isPresent();
        assertThat(second).isEmpty();
        verify(bucket, times(2)).getAndDelete();
    }

    @Test
    @DisplayName("令牌归属不符 → 消费后拒绝，避免他人重放")
    void rejectOtherUsersToken() throws Exception {
        when(bucket.getAndDelete())
                .thenReturn(objectMapper.writeValueAsString(action("owner")));

        assertThat(service.consume("cfm_1", "attacker")).isEmpty();
        verify(bucket).getAndDelete();
    }

    @Test
    @DisplayName("令牌已过期 → 拒绝执行")
    void rejectExpiredToken() throws Exception {
        ConfirmationService.PendingAction expired = new ConfirmationService.PendingAction(
                "cfm_1", "demo", "c1", "model-x", "cancel_order",
                List.of(), "已过期", "WRITE", System.currentTimeMillis() - 1);
        when(bucket.getAndDelete()).thenReturn(objectMapper.writeValueAsString(expired));

        assertThat(service.consume("cfm_1", "demo")).isEmpty();
    }

    @Test
    @DisplayName("Redis 消费异常 → fail-closed，不返回可执行令牌")
    void consumeFailsClosedOnRedisError() {
        when(bucket.getAndDelete()).thenThrow(new RuntimeException("redis down"));

        assertThat(service.consume("cfm_1", "demo")).isEmpty();
    }

    @Test
    @DisplayName("Redis 创建异常 → fail-closed，抛出缓存不可用")
    void createFailsClosedOnRedisError() {
        doThrow(new RuntimeException("redis down"))
                .when(bucket).set(anyString(), anyLong(), eq(TimeUnit.SECONDS));

        assertThatThrownBy(() -> service.create(
                "demo", "c1", "model-x", "cancel_order", List.of(),
                "即将执行：取消订单。", "WRITE"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE.getCode());
    }
}
