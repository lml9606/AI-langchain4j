package com.example.langchain4j.guard;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Redis 分布式会话锁（Redisson RLock）。
 *
 * 同一会话（thread_id）同一时刻只允许一个图执行，防止 checkpoint 并发写竞态。宽松模式
 * 供 chat/resume 使用，显式租约（agent.lock.lease-seconds，默认 300s），Redis 故障
 * fail-open，异常路径漏解锁时锁最迟在租约到期后自愈。严格模式供 confirm 确认写操作使用，
 * Redisson Watchdog 自动续期，Redis 故障 fail-closed，绝不在无锁状态下执行写操作。
 *
 * 两种模式均可重入，释放前校验 isHeldByCurrentThread，避免误释放他人锁。
 * 宽松模式的租约必须大于最长图执行耗时（execution-timeout-seconds）。
 */
@Slf4j
@Component
public class SessionLock {

    private static final String KEY_PREFIX = "agent:lock:";

    private final RedissonClient redissonClient;
    private final long leaseSeconds;

    public SessionLock(RedissonClient redissonClient, AgentProperties properties) {
        this.redissonClient = redissonClient;
        // 配置兜底：至少 1 秒，避免误配 0/负数导致 tryLock 直接抛异常
        this.leaseSeconds = Math.max(1, properties.getLock().getLeaseSeconds());
    }

    /**
     * 在会话锁保护下执行任务。
     *
     * @param lockKey 锁标识（如 conversationId），将拼入 Redis key
     * @param action  受保护的任务
     * @return 任务返回值
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> action) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + sanitize(lockKey));
        boolean locked = tryAcquire(lock);
        if (!locked) {
            log.warn("[session-lock] lock conflict, key={}", lock.getName());
            throw new BizException(ErrorCode.AGENT_SESSION_LOCKED);
        }
        try {
            return action.get();
        } finally {
            releaseQuietly(lock);
        }
    }

    /**
     * 在严格会话锁保护下执行任务，供确认写操作专用。
     *
     * Redis 故障或加锁中断都按 fail-closed 抛出业务异常，不会在无锁状态下执行 action。
     *
     * @param lockKey 锁标识（如 conversationId），将拼入 Redis key
     * @param action  受保护的任务
     * @return 任务返回值
     */
    public <T> T executeWithLockStrict(String lockKey, Supplier<T> action) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + sanitize(lockKey));
        boolean locked = tryAcquireStrict(lock);
        if (!locked) {
            log.warn("[session-lock] strict lock conflict, key={}", lock.getName());
            throw new BizException(ErrorCode.AGENT_SESSION_LOCKED);
        }
        try {
            return action.get();
        } finally {
            releaseQuietly(lock);
        }
    }

    /**
     * 立即尝试加锁（不等待）：成功返回 true；锁冲突返回 false；Redis 故障 fail-open 返回 true。
     *
     * tryLock(0, leaseSeconds, SECONDS) 的两个行为：
     * waitTime=0 表示不排队等待，抢不到立即返回 false，避免请求堆积；leaseTime>0 表示
     * 显式租约，到期自动释放，不启用 Watchdog 续期。异常路径漏解锁时锁可自愈，代价是
     * 超过租约的长任务会被提前解锁，因此 leaseSeconds 必须大于 executionTimeoutSeconds。
     */
    private boolean tryAcquire(RLock lock) {
        try {
            return lock.tryLock(0, leaseSeconds, TimeUnit.SECONDS);
        } catch (Exception e) {
            // fail-open：Redis 不可用时降级为无锁执行，不阻塞业务
            log.warn("[session-lock] redisson unavailable, fail-open: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 严格模式加锁：只传 waitTime，leaseTime 交给 Redisson Watchdog 自动续期。
     *
     * Redis 故障和中断都 fail-closed，确认写操作绝不在无锁状态下执行。
     */
    private boolean tryAcquireStrict(RLock lock) {
        try {
            return lock.tryLock(0, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[session-lock] strict acquire interrupted, key={}", lock.getName());
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "会话锁获取被中断");
        } catch (Exception e) {
            log.error("[session-lock] strict acquire failed, fail-closed, key={}: {}",
                    lock.getName(), e.getMessage());
            throw new BizException(ErrorCode.CACHE_UNAVAILABLE, "会话锁服务暂不可用，请稍后重试");
        }
    }

    private void releaseQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[session-lock] release failed (lease will expire it): {}", e.getMessage());
        }
    }

    private String sanitize(String lockKey) {
        if (lockKey == null || lockKey.isBlank()) {
            return "default";
        }
        return lockKey.replaceAll("[^a-zA-Z0-9._:-]", "_");
    }
}
