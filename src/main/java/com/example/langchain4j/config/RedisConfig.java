package com.example.langchain4j.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Redis 配置：启动时探测连通性。
 * <p>
 * StringRedisTemplate 由 Spring Boot 自动配置提供；这里仅做启动期健康探测，
 * 不可用时只告警不阻断（限流/锁/缓存均为 fail-open 策略）。
 */
@Slf4j
@Configuration
public class RedisConfig {

    @Bean
    public ApplicationRunner redisHealthProbe(StringRedisTemplate redisTemplate) {
        return (ApplicationArguments args) -> {
            try {
                String pong = redisTemplate.getConnectionFactory().getConnection().ping();
                log.info("[redis] connectivity OK, ping={}", pong);
            } catch (Exception e) {
                log.warn("[redis] unavailable, rate-limit/session-lock/cache will fail-open: {}",
                        e.getMessage());
            }
        };
    }
}
