package com.example.langchain4j.cache;

import com.example.langchain4j.common.util.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;

/**
 * 轻量缓存封装（Redis 字符串 + JSON）。
 * <ul>
 *   <li>所有操作 fail-open：Redis 故障时读返回空、写静默降级，不阻断主流程；</li>
 *   <li>反序列化失败视为缓存未命中（回源重建），避免脏缓存污染业务。</li>
 * </ul>
 */
@Slf4j
@Component
public class CacheService {

    private static final Duration DEFAULT_TTL = Duration.ofMinutes(10);

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public CacheService(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = JsonUtil.copyMapper();
    }

    /** 读字符串缓存，Redis 故障或未命中返回 empty */
    public Optional<String> get(String key) {
        try {
            return Optional.ofNullable(redisTemplate.opsForValue().get(key));
        } catch (Exception e) {
            log.warn("[cache] get failed, key={}, fail-open: {}", key, e.getMessage());
            return Optional.empty();
        }
    }

    public void set(String key, String value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, value, ttl == null ? DEFAULT_TTL : ttl);
        } catch (Exception e) {
            log.warn("[cache] set failed, key={}, fail-open: {}", key, e.getMessage());
        }
    }

    public void delete(String key) {
        try {
            redisTemplate.delete(key);
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
}
