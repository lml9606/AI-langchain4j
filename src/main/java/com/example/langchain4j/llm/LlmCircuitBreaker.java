package com.example.langchain4j.llm;

import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 按模型隔离的 Redis 熔断器。
 * <p>
 * 每个模型独立失败计数和打开标记：A 模型故障不会让 B 模型一起熔断，
 * 模型 B 成功也不会清掉 A 的失败计数。Redis 故障时 fail-open。
 */
@Slf4j
@Component
public class LlmCircuitBreaker {

    private static final String FAILURES_PREFIX = "agent:llm:cb:failures:";
    private static final String OPEN_PREFIX = "agent:llm:cb:open:";

    private final RedissonClient redissonClient;
    private final AgentProperties properties;
    private final AgentMetrics metrics;

    public LlmCircuitBreaker(RedissonClient redissonClient, AgentProperties properties,
                             AgentMetrics metrics) {
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.metrics = metrics;
    }

    /** 判断指定模型熔断是否打开；Redis 异常 fail-open。 */
    public boolean isOpen(String modelName) {
        try {
            return redissonClient.getBucket(openKey(modelName)).isExists();
        } catch (Exception e) {
            log.warn("[llm] circuit breaker redis unavailable, fail-open: {}", e.getMessage());
            return false;
        }
    }

    /** 成功调用只清理当前模型的失败计数。 */
    public void onSuccess(String modelName) {
        metrics.setCircuitOpen(modelName, false);
        try {
            redissonClient.getBucket(failuresKey(modelName)).delete();
        } catch (Exception ignored) {
            // fail-open：Redis 异常不影响主流程
        }
    }

    /** 失败耗尽后累计当前模型失败次数，达到阈值则只打开当前模型。 */
    public void onFailure(String modelName) {
        try {
            long failures = redissonClient.getAtomicLong(failuresKey(modelName)).incrementAndGet();
            if (failures >= properties.getLlm().getCircuitBreaker().getFailureThreshold()) {
                redissonClient.getBucket(openKey(modelName)).set("1",
                        properties.getLlm().getCircuitBreaker().getCooldownSeconds(), TimeUnit.SECONDS);
                redissonClient.getBucket(failuresKey(modelName)).delete();
                metrics.setCircuitOpen(modelName, true);
                log.warn("[llm] circuit breaker OPEN, model={}, cooldown={}s",
                        modelName, properties.getLlm().getCircuitBreaker().getCooldownSeconds());
            }
        } catch (Exception e) {
            log.warn("[llm] circuit breaker redis unavailable, skip counting: {}", e.getMessage());
        }
    }

    private String failuresKey(String modelName) {
        return FAILURES_PREFIX + safeModel(modelName);
    }

    private String openKey(String modelName) {
        return OPEN_PREFIX + safeModel(modelName);
    }

    private String safeModel(String modelName) {
        if (modelName == null || modelName.isBlank()) {
            return "unknown";
        }
        return modelName.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
