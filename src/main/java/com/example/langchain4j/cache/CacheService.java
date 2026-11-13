package com.example.langchain4j.cache;

import com.example.langchain4j.common.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * 轻量缓存封装（Redisson RBucket + JSON，全局 StringCodec 存纯字符串）。
 * <ul>
 *   <li>所有操作 fail-open：Redis 故障时读返回空、写静默降级，不阻断主流程；</li>
 *   <li>反序列化失败视为缓存未命中（回源重建），避免脏缓存污染业务。</li>
 * </ul>
 */
@Slf4j
@Component
public class CacheService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;

    public CacheService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
        this.objectMapper = JsonUtil.copyMapper();
    }

    /** 读字符串缓存，Redis 故障或未命中返回 empty */
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(bucket(key).get());
        } catch (Exception e) {
            log.warn("[cache] get failed, key={}, fail-open: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void set(String key, String value, Duration ttl) {
        try {
            Duration effectiveTtl = ttl == null ? DEFAULT_TTL : ttl;
            bucket(key).set(value, effectiveTtl.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            log.warn("[cache] set failed, key={}, fail-open: {}", key, e.getMessage());
        }
    }

    public void delete(String key) {
        try {
            bucket(key).delete();
        } catch (Exception e) {
            log.warn("[cache] delete failed, key={}, fail-open: {}", key, e.getMessage());
        }
    }

    /** 读对象缓存（JSON 反序列化），任何异常视为未命中 */
    public <T> Optional<T> getObject(String key, Class<T> type) {
        return get(key).flatMap(json -> {
            try {
                return Optional.ofNullable(objectMapper.readValue(json, type));
            } catch (Exception e) {
                log.warn("[cache] deserialize failed, key={}, treat as miss: {}", key, e.getMessage());
                return Optional.empty();
            }
        });
    }

    public void setObject(String key, Object value, Duration ttl) {
        if (value == null) {
            delete(key);
            return;
        }
        try {
            set(key, objectMapper.writeValueAsString(value), ttl);
        } catch (Exception e) {
            log.warn("[cache] serialize failed, key={}, skip cache: {}", key, e.getMessage());
        }
    }

    /** 取桶：全局 Codec 已统一 StringCodec（RedisConfig），写入纯字符串，保证与历史数据/运维工具可读兼容 */
    private RBucket<String> bucket(String key) {
        return redissonClient.getBucket(key);
    }
}
