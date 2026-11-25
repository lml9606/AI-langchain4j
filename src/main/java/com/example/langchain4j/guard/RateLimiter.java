package com.example.langchain4j.guard;

import com.example.langchain4j.common.constant.RedisKeys;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.api.RScript;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.List;

/**
 * 固定窗口限流器（Redisson RScript 原子计数实现）。
 *
 * INCR 和首次 EXPIRE 通过 Lua 脚本保证原子性，避免窗口边界竞态；Redis 故障时默认
 * fail-open 放行，可用性优先，可配置关闭；维度由调用方决定（userId、IP、工具维度等），
 * 本类只负责计数与判定。
 */
@Slf4j
@Component
public class RateLimiter {

    private static final Duration WINDOW = Duration.ofMinutes(1);

    /** Lua：INCR 并仅在首次设置过期时间（原子） */
    private static final String INCR_SCRIPT =
            "local c = redis.call('INCR', KEYS[1]) " +
            "if c == 1 then redis.call('EXPIRE', KEYS[1], ARGV[1]) end " +
            "return c";

    private final RedissonClient redissonClient;
    private final AgentProperties properties;
    private final AgentMetrics metrics;

    public RateLimiter(RedissonClient redissonClient, AgentProperties properties, AgentMetrics metrics) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 尝试获取一个默认额度令牌（聊天等通用维度使用 agent.rate-limit.permits-per-minute）。
     *
     * @param dimension 限流维度标识（如 userId、IP、tool:userId），将拼入 Redis key
     * @return true=放行（含限流禁用与 Redis 故障 fail-open）；false=拒绝（触发限流或 fail-closed）
     */
    public boolean tryAcquire(String dimension) {
        return tryAcquire(dimension, properties.getRateLimit().getPermitsPerMinute());
    }

    /**
     * 尝试获取一个指定额度令牌（工具等独立维度传入自己的每分钟上限）。
     *
     * @param dimension 限流维度标识，将拼入 Redis key
     * @param limit     窗口内允许次数；非正数视为关闭该维度限流
     * @return true=放行（含限流禁用与 Redis 故障 fail-open）；false=拒绝（触发限流或 fail-closed）
     */
    public boolean tryAcquire(String dimension, int limit) {
        if (!properties.getRateLimit().isEnabled() || limit <= 0) {
            return true;
        }
        String key = RedisKeys.RATE_LIMIT_PREFIX + sanitize(dimension);
        try {
            // EVAL 原子执行：KEYS[1]=计数键，ARGV[1]=窗口秒数（Codec 由全局配置 StringCodec 兜底）
            Long count = redissonClient.getScript().eval(
                    RScript.Mode.READ_WRITE, INCR_SCRIPT, RScript.ReturnType.INTEGER,
                    List.<Object>of(key), String.valueOf(WINDOW.getSeconds()));
            boolean allowed = count == null || count <= limit;
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
