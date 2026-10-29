package com.example.langchain4j.guard;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 固定窗口限流器（Redis 原子计数实现）。
 * <ul>
 *   <li>INCR + 首次 EXPIRE 通过 Lua 脚本保证原子性，避免窗口边界竞态；</li>
 *   <li>Redis 故障时 fail-open（放行），可用性优先，可配置关闭；</li>
 *   <li>维度由调用方决定（userId / IP），本类只负责计数与判定。</li>
 * </ul>
 */
@Slf4j
@Component
public class RateLimiter {

    private static final String KEY_PREFIX = "agent:rl:";
    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Lua：INCR 并仅在首次设置过期时间（原子） */
    private static final String INCR_SCRIPT =
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return c";

    private final StringRedisTemplate redisTemplate;
    private final AgentProperties properties;
    private final AgentMetrics metrics;
    private final DefaultRedisScript<Long> incrScript;

    public RateLimiter(StringRedisTemplate redisTemplate, AgentProperties properties, AgentMetrics metrics) {
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.metrics = metrics;
        this.incrScript = new DefaultRedisScript<>(INCR_SCRIPT, Long.class);
    }

    /**
     * 尝试获取一个令牌（计数 +1）。
     *
     * @param dimension 限流维度标识（如 userId、IP），将拼入 Redis key
     * @return true=放行（含限流禁用与 Redis 故障 fail-open）；false=拒绝（触发限流或 fail-closed）
     */
    public boolean tryAcquire(String dimension) {
        if (!properties.getRateLimit().isEnabled()) {
            return true;
        }
        String key = KEY_PREFIX + sanitize(dimension);
        try {
            Long count = redisTemplate.execute(incrScript, List.of(key),
                    String.valueOf(WINDOW.getSeconds()));
            boolean allowed = count == null || count <= properties.getRateLimit().getPermitsPerMinute();
            if (!allowed) {
                metrics.recordRateLimited();
                log.warn("[rate-limit] rejected, key={}, count={}", key, count);
            }
            return allowed;
        } catch (Exception e) {
            // fail-open：Redis 故障不影响业务可用性（可配置关闭以切换到严格模式）
            if (properties.getRateLimit().isFailOpen()) {
                log.warn("[rate-limit] redis unavailable, fail-open: {}", e.getMessage());
                return true;
            }
            log.error("[rate-limit] redis unavailable, fail-closed", e);
            return false;
        }
    }

    /** 获取令牌，超限直接抛业务异常（供 service 层调用） */
    public void checkAcquire(String dimension) {
        if (!tryAcquire(dimension)) {
            throw new BizException(ErrorCode.AGENT_RATE_LIMITED);
        }
    }

    /** 防止 key 注入非法字符 */
    private String sanitize(String dimension) {
        if (dimension == null || dimension.isBlank()) {
            return "anonymous";
        }
        return dimension.replaceAll("[^a-zA-Z0-9._:-]", "_");
    }
}
