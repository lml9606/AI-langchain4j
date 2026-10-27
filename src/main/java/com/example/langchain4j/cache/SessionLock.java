package com.example.langchain4j.cache;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.function.Supplier;

/**
 * Redis 分布式会话锁（Redisson RLock 实现）。
 * <p>
 * 用途：同一会话（thread_id）同一时刻只允许一个图执行，防止 checkpoint 并发写竞态。
 * <ul>
 *   <li>Watchdog 看门狗：默认租约 30s 自动续期，任务跑多久锁持多久（线程崩溃时最多 30s 后自动释放），
 *       根治手写 SETNX 固定 TTL 的“锁先于任务过期”竞态；</li>
 *   <li>可重入：同一线程重复加锁不会死锁；</li>
 *   <li>Redis 故障时 fail-open（不锁直接执行），可用性优先；</li>
 *   <li>释放前校验 isHeldByCurrentThread，避免误释放他人锁。</li>
 * </ul>
 */
@Slf4j
@Component
public class SessionLock {

    private static final String KEY_PREFIX = "agent:lock:";

    private final RedissonClient redissonClient;

    public SessionLock(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
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
     * 调用方在 close() 时释放锁；若异常中断未 close，锁由 Watchdog 在持锁线程消亡后自动过期兜底。
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

    /** 立即尝试加锁（不等待）：成功返回 true；锁冲突返回 false；Redis 故障 fail-open 返回 true */
    private boolean tryAcquire(RLock lock) {
        try {
            return lock.tryLock();
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
