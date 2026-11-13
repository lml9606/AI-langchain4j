package com.example.langchain4j.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.codec.StringCodec;
import org.redisson.spring.starter.RedissonAutoConfigurationCustomizer;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redis 配置：统一客户端为 Redisson（不再使用 Lettuce/StringRedisTemplate）+ 启动期连通性探测。
 * <ul>
 *   <li>全局 Codec 固定 StringCodec：限流计数、熔断标记、缓存值均为纯字符串，可读且兼容历史数据；</li>
 *   <li>不可用时只告警不阻断（限流/锁/缓存/熔断均为 fail-open 策略）。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class RedisConfig {

    /**
     * 覆盖 Redisson 默认的二进制 Codec：业务侧只存字符串（计数/标记/JSON），
     * StringCodec 保证可读，且 Lua 脚本的 KEYS/ARGV 不会被打包成二进制导致 INCR 失败。
     * <p>
     * 附带空串密码归一：`spring.data.redis.password` 空串（未配环境变量时的 `${REDIS_PASSWORD:}` 占位符
     * 或 properties 里显式空值）非 null，Redisson 对非 null 密码一律发 AUTH 命令，
     * 本机/生产 Redis 未设 requirepass 时返回 "ERR Client sent AUTH, but no password is set" 导致连接失败；
     * 归一为 null 后 Redisson 跳过 AUTH。
     */
    @Bean
    public RedissonAutoConfigurationCustomizer redissonCodecCustomizer() {
        return config -> {
            config.setCodec(StringCodec.INSTANCE);
            String password = config.useSingleServer().getPassword();
            if (password != null && password.isBlank()) {
                config.useSingleServer().setPassword(null);
            }
        };
    }

    @Bean
    public ApplicationRunner redisHealthProbe(RedissonClient redissonClient) {
        return (ApplicationArguments args) -> {
            try {
                boolean pong = redissonClient.getNodesGroup().pingAll();
                if (pong) {
                    log.info("[redis] connectivity OK");
                } else {
                    log.warn("[redis] ping failed, rate-limit/session-lock/cache will fail-open");
                }
            } catch (Exception e) {
                log.warn("[redis] unavailable, rate-limit/session-lock/cache will fail-open: {}",
                        e.getMessage());
            }
        };
    }
}
