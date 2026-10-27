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

    /** DeepSeek 官方 API 配置（OpenAI 兼容协议） */
    private Deepseek deepseek = new Deepseek();

    /** 限流配置 */
    private RateLimit rateLimit = new RateLimit();

    /** 校验重试上限（图内重试环次数） */
    private int maxValidateRetries = 3;

    /** 单次图执行整体超时（秒）；超时后任务在后台继续跑完，会话锁由 Redisson Watchdog 自动续期兜底 */
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
        /** 默认模型名：前端不传 model 参数时使用；必须是注册表已注册的模型 */
        private String defaultModel = "deepseek-flash";
        /** 千问模型名（与 langchain4j.community.dashscope.chat-model.model-name 保持一致） */
        private String qwenModelName = "qwen-max";
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
    }

    @Data
    public static class Deepseek {
        /** DeepSeek 官方 API Key（环境变量 DEEPSEEK_API_KEY 注入，避免入库） */
        private String apiKey = "";
        /** OpenAI 兼容协议接入地址 */
        private String baseUrl = "https://api.deepseek.com";
        /** 模型名：deepseek-flash（V4.1-Flash，性价比）；需要更强推理可换 deepseek-v4-pro */
        private String modelName = "deepseek-flash";
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
}
