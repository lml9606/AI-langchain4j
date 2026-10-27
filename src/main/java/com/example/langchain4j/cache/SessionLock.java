package com.example.langchain4j.cache;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Redis 分布式会话锁（SETNX + TTL）。
 * <p>
 * 用途：同一会话（thread_id）同一时刻只允许一个图执行，防止 checkpoint 并发写竞态。
 * <ul>
 *   <li>锁带 TTL 自动过期，防止持有者崩溃后死锁；</li>
 *   <li>释放时校验锁值（Lua 原子），只删除自己持有的锁；</li>
 *   <li>Redis 故障时 fail-open（不锁直接执行），可用性优先。</li>
 * </ul>
 */
@Slf4j
@Component
public class SessionLock {

    private static final String KEY_PREFIX = "agent:lock:";

    /** Lua：值匹配才删除（原子，防误删他人锁） */
    private static final String RELEASE_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
            "return redis.call('DEL', KEYS[1]) else return 0 end";

    private final StringRedisTemplate redisTemplate;
    private final AgentProperties properties;
    private final DefaultRedisScript<Long> releaseScript;

    public SessionLock(StringRedisTemplate redisTemplate, AgentProperties properties) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.releaseScript = new DefaultRedisScript<>(RELEASE_SCRIPT, Long.class);
    }

    /**
     * 在会话锁保护下执行任务。
     *
     * @param lockKey 锁标识（如 conversationId），将拼入 Redis key
     * @param action  受保护的任务
     * @return 任务返回值
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> action) {
        String key = KEY_PREFIX + sanitize(lockKey);
        String token = UUID.randomUUID().toString();
        boolean locked = false;
        try {
            locked = acquire(key, token);
            if (!locked) {
                log.warn("[session-lock] lock conflict, key={}", key);
                throw new BizException(ErrorCode.AGENT_SESSION_LOCKED);
            }
            return action.get();
        } finally {
            if (locked) {
                releaseQuietly(key, token);
            }
        }
    }

    /**
     * 手动加锁（供流式场景跨迭代持锁）：
     * 调用方在 close() 时释放锁；若异常中断未 close，锁由 TTL 自动过期兜底。
     */
    public AutoCloseable lock(String lockKey) {
        String key = KEY_PREFIX + sanitize(lockKey);
        String token = UUID.randomUUID().toString();
        boolean locked = acquire(key, token);
        if (!locked) {
            log.warn("[session-lock] lock conflict, key={}", key);
            throw new BizException(ErrorCode.AGENT_SESSION_LOCKED);
        }
        return () -> releaseQuietly(key, token);
    }

    private boolean acquire(String key, String token) {
        try {
            long timeoutMs = Math.max(1000L, properties.getSessionLock().getTimeoutMs());
            Boolean ok = redisTemplate.opsForValue()
                    .setIfAbsent(key, token, Duration.ofMillis(timeoutMs));
            return Boolean.TRUE.equals(ok);
        } catch (Exception e) {
            // fail-open：Redis 不可用时降级为无锁执行，不阻塞业务
            log.warn("[session-lock] redis unavailable, fail-open: {}", e.getMessage());
            return true;
        }
    }

    private void releaseQuietly(String key, String token) {
        try {
            redisTemplate.execute(releaseScript, List.of(key), token);
        } catch (Exception e) {
            log.warn("[session-lock] release failed (lock will expire by TTL): {}", e.getMessage());
        }
    }

    private String sanitize(String lockKey) {
        if (lockKey == null || lockKey.isBlank()) {
            return "default";
        }
        return lockKey.replaceAll("[^a-zA-Z0-9._:-]", "_");
    }
}
