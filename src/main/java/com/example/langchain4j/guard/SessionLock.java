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
 * Redis 分布式会话锁（Redisson RLock + 显式租约实现）。
 * <p>
 * 用途：同一会话（thread_id）同一时刻只允许一个图执行，防止 checkpoint 并发写竞态。
 * <ul>
 *   <li>显式租约：租约到期自动释放（agent.lock.lease-seconds，默认 300s），
 *       异常路径漏解锁时锁最迟在租约到期后自愈，不会因 Watchdog 无限续期而永久锁死；</li>
 *   <li>可重入：同一线程重复加锁不会死锁；</li>
 *   <li>Redis 故障时 fail-open（不锁直接执行），可用性优先；</li>
 *   <li>释放前校验 isHeldByCurrentThread，避免误释放他人锁。</li>
 * </ul>
 * 代价：显式租约不自动续期，任务超过租约会被提前解锁，
 * 因此租约必须大于最长图执行耗时（execution-timeout-seconds / resume.timeout-seconds）。
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
     * 手动加锁（供流式场景跨迭代持锁）：
     * 调用方在 close() 时释放锁；若异常中断未 close，锁在租约到期后自动释放。
     */
    public AutoCloseable lock(String lockKey) {
        RLock lock = redissonClient.getLock(KEY_PREFIX + sanitize(lockKey));
        boolean locked = tryAcquire(lock);
        if (!locked) {
            log.warn("[session-lock] lock conflict, key={}", lock.getName());
            throw new BizException(ErrorCode.AGENT_SESSION_LOCKED);
        }
        return () -> releaseQuietly(lock);
    }

    /**
     * 立即尝试加锁（不等待）：成功返回 true；锁冲突返回 false；Redis 故障 fail-open 返回 true。
     * <p>
     * tryLock(0, leaseSeconds, SECONDS) 的两个行为：
     * <ul>
     *   <li>waitTime=0：不排队等待，抢不到立即返回 false（避免请求堆积）；</li>
     *   <li>leaseTime>0：显式租约，到期自动释放，不启用 Watchdog 续期。
     *       异常路径漏解锁时锁可自愈；代价是超过租约的长任务会被提前解锁，
     *       因此 leaseSeconds 必须大于 executionTimeoutSeconds。</li>
     * </ul>
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

    private void releaseQuietly(RLock lock) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (Exception e) {
            log.warn("[session-lock] release failed (watchdog will expire it): {}", e.getMessage());
        }
    }

    private String sanitize(String lockKey) {
        if (lockKey == null || lockKey.isBlank()) {
            return "default";
        }
        return lockKey.replaceAll("[^a-zA-Z0-9._:-]", "_");
    }
}
