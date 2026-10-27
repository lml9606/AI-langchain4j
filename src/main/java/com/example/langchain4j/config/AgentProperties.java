package com.example.langchain4j.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Agent 运行参数（application.properties 中 agent.* 前缀）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "agent")
public class AgentProperties {

    /** Checkpoint 配置 */
    private Checkpoint checkpoint = new Checkpoint();

    /** 图执行线程池配置 */
    private Executor executor = new Executor();

    /** LLM 网关防护配置 */
    private Llm llm = new Llm();

    /** 限流配置 */
    private RateLimit rateLimit = new RateLimit();

    /** 会话锁配置 */
    private SessionLock sessionLock = new SessionLock();

    /** 校验重试上限（图内重试环次数） */
    private int maxValidateRetries = 3;

    /** 单次图执行整体超时（秒，需小于会话锁 TTL，保证锁不外泄） */
    private int executionTimeoutSeconds = 240;

    @Data
    public static class Checkpoint {
        /** PostgresSaver 不可用时是否降级内存 saver */
        private boolean memoryFallback = true;
    }

    @Data
    public static class Executor {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 200;
        private int keepAliveSeconds = 60;
    }

    @Data
    public static class Llm {
        private int timeoutSeconds = 60;
        private int maxRetries = 3;
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
    }

    @Data
    public static class CircuitBreaker {
        private int failureThreshold = 5;
        private int cooldownSeconds = 60;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int permitsPerMinute = 60;
        private boolean failOpen = true;
    }

    @Data
    public static class SessionLock {
        private long timeoutMs = 120_000L;
    }
}
