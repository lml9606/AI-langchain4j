package com.example.langchain4j.guard;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 会话锁单测：宽松模式保持 fail-open，严格模式使用 Watchdog 且 Redis 故障 fail-closed。
 */
@ExtendWith(MockitoExtension.class)
class SessionLockTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private SessionLock sessionLock;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getLock().setLeaseSeconds(300);
        sessionLock = new SessionLock(redissonClient, properties);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    @Test
    @DisplayName("严格模式 → 使用 Watchdog 加锁，执行后释放")
    void strictUsesWatchdogAndReleases() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        String result = sessionLock.executeWithLockStrict("c1", () -> "ok");

        assertThat(result).isEqualTo("ok");
        verify(lock).tryLock(0, TimeUnit.SECONDS);
        verify(lock).unlock();
    }

    @Test
    @DisplayName("严格模式锁冲突 → 409，不执行任务")
    void strictConflictRejects() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> sessionLock.executeWithLockStrict("c1", () -> "ok"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.AGENT_SESSION_LOCKED.getCode());
    }

    @Test
    @DisplayName("严格模式 Redis 故障 → 503，不执行任务")
    void strictFailsClosedOnRedisError() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenThrow(new RuntimeException("redis down"));

        assertThatThrownBy(() -> sessionLock.executeWithLockStrict("c1", () -> "ok"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE.getCode());
    }

    @Test
    @DisplayName("宽松模式 Redis 故障 → fail-open，仍然执行")
    void lenientFailsOpenOnRedisError() throws Exception {
        when(lock.tryLock(0, 300L, TimeUnit.SECONDS)).thenThrow(new RuntimeException("redis down"));
        when(lock.isHeldByCurrentThread()).thenReturn(false);

        String result = sessionLock.executeWithLock("c1", () -> "ok");

        assertThat(result).isEqualTo("ok");
    }
}
