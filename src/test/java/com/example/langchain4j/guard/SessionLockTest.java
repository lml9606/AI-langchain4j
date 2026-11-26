package com.example.langchain4j.guard;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 会话锁测评：普通链路 Redis 故障可用性优先，确认写链路必须 fail-closed。 */
@ExtendWith(MockitoExtension.class)
class SessionLockTest {

    @Mock
    private RedissonClient redissonClient;
    @Mock
    private RLock lock;

    private SessionLock sessionLock;

    /** 构造固定租约的会话锁，并绑定唯一 RLock。 */
    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getLock().setLeaseSeconds(300);
        sessionLock = new SessionLock(redissonClient, properties);
        when(redissonClient.getLock(anyString())).thenReturn(lock);
    }

    /** 普通链路拿到锁后执行任务并释放锁。 */
    @Test
    void normalLockExecutesAndReleases() throws Exception {
        when(lock.tryLock(0, 300, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThat(sessionLock.executeWithLock("c1", () -> "ok")).isEqualTo("ok");
        verify(lock).unlock();
    }

    /** 普通链路锁冲突立即拒绝，不排队等待。 */
    @Test
    void normalLockConflictIsRejected() throws Exception {
        when(lock.tryLock(0, 300, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> sessionLock.executeWithLock("c1", () -> "never"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_SESSION_LOCKED.getCode());
    }

    /** 普通链路 Redis 故障 fail-open，保证只读对话仍可继续。 */
    @Test
    void normalLockFailsOpenWhenRedisUnavailable() throws Exception {
        when(lock.tryLock(0, 300, TimeUnit.SECONDS))
                .thenThrow(new IllegalStateException("redis down"));

        assertThat(sessionLock.executeWithLock("c1", () -> "ok")).isEqualTo("ok");
        verify(lock, never()).unlock();
    }

    /** 严格链路 Redis 故障必须拒绝执行确认写操作。 */
    @Test
    void strictLockFailsClosedWhenRedisUnavailable() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS))
                .thenThrow(new IllegalStateException("redis down"));
        AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> sessionLock.executeWithLockStrict("c1", () -> {
            calls.incrementAndGet();
            return "never";
        }))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.CACHE_UNAVAILABLE.getCode());
        assertThat(calls.get()).isZero();
    }

    /** 严格链路成功加锁后执行任务并释放。 */
    @Test
    void strictLockExecutesAndReleases() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(true);
        when(lock.isHeldByCurrentThread()).thenReturn(true);

        assertThat(sessionLock.executeWithLockStrict("c1", () -> "ok")).isEqualTo("ok");
        verify(lock).unlock();
    }

    /** 严格链路拿不到锁时拒绝执行，避免并发确认重复写。 */
    @Test
    void strictLockConflictIsRejected() throws Exception {
        when(lock.tryLock(0, TimeUnit.SECONDS)).thenReturn(false);

        assertThatThrownBy(() -> sessionLock.executeWithLockStrict("c1", () -> "never"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.AGENT_SESSION_LOCKED.getCode());
    }
}
