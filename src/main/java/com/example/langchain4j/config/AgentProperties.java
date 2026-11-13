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

    /** LLM 调用线程池配置（与图执行线程池隔离） */
    private LlmExecutor llmExecutor = new LlmExecutor();

    /** LLM 网关防护配置 */
    private Llm llm = new Llm();

    /** DeepSeek 官方 API 配置（OpenAI 兼容协议） */
    private Deepseek deepseek = new Deepseek();

    /** 本地向量模型配置（Ollama 部署 BGE-M3） */
    private Embedding embedding = new Embedding();

    /** 知识治理配置（分块/批量导入） */
    private Knowledge knowledge = new Knowledge();

    /** 知识导入线程池配置 */
    private KnowledgeImport knowledgeImport = new KnowledgeImport();

    /** 检索配置（父子装配上下文预算） */
    private Retrieval retrieval = new Retrieval();

    /** 限流配置 */
    private RateLimit rateLimit = new RateLimit();

    /** 会话锁配置 */
    private Lock lock = new Lock();

    /** 校验重试上限（图内重试环次数） */
    private int maxValidateRetries = 3;

    /** 多跳检索上限（不含首轮；HopCheck 判定信息不足时最多再检索 maxHops 轮，防成本失控） */
    private int maxHops = 2;

    /** 问题分解子查询上限（DecomposeNode 白名单校验，超限只取前 N 个） */
    private int maxSubQueries = 3;

    /** 多轮上下文（短期记忆）配置 */
    private History history = new History();

    /** 简历优化工作流配置 */
    private Resume resume = new Resume();

    /** 单次图执行整体超时（秒）；超时即中止本轮：取消后台任务并返回降级应答，一问一答照常闭环，用户可直接重发 */
    private int executionTimeoutSeconds = 240;

    @Data
    public static class Checkpoint {
        /** PostgresSaver 不可用时是否降级内存 saver */
        private boolean memoryFallback = true;
    }

    /**
     * 多轮上下文（短期记忆）分层配置：
     * 改写视图（图外指代消解）与生成视图（图内原文回放）分开预算，段摘要异步压缩超窗历史。
     */
    @Data
    public static class History {
        /** 改写视图：最近消息条数（近 2 轮足够做指代消解，窗口小改写调用快） */
        private int rewriteWindowSize = 4;
        /** 改写视图：单条消息最大字符数（要点即可，超长截断） */
        private int rewriteItemMaxLength = 800;
        /** 生成视图：历史注入的 token 估算预算（系统提示词/当前query/检索上下文另有预算） */
        private int generationMaxTokens = 8000;
        /** 生成视图：单条消息最大字符数（原文细节） */
        private int generationItemMaxLength = 4000;
        /** 触发增量摘要的阈值：段内未被摘要覆盖的消息条数达到该值即异步摘要 */
        private int summaryTriggerMessages = 10;
        /** 单次摘要任务最多压缩的消息条数（防 LLM 输入过大） */
        private int summaryMaxMessagesPerRun = 40;
        /** 段摘要最大字符数（超长截断，防止摘要本身膨胀） */
        private int summaryMaxLength = 4000;
    }

    /**
     * 简历优化配置：输入上限、输出预算、目标岗位数量与校验重试上限。
     * <p>
     * 输出预算必须独立于 {@link Llm#getMaxOutputTokens()}：后者 1024 只适合短 JSON 节点，
     * 整份简历改稿通常需要数千 token，共用一个值会稳定截断。
     */
    @Data
    public static class Resume {
        /** 简历原文最大字符数，超出截尾并在提示词中明示 */
        private int maxInputChars = 20_000;
        /** 首次生成的输出 token 上限 */
        private int maxOutputTokens = 8_192;
        /** 输出被截断时的重试 token 上限 */
        private int retryMaxOutputTokens = 12_288;
        /** 定向优化单次最多参与的目标岗位数（超过 representativeCount 时走聚合画像） */
        private int maxTargetJobs = 10;
        /** 单个岗位 JD 参与提示词的最大字符数 */
        private int maxTargetJdChars = 1_200;
        /** 岗位数超过该值时走聚合画像 + 代表 JD 策略 */
        private int representativeCount = 5;
        /** 校验失败后的图内重试上限 */
        private int maxRetries = 2;
        /** 变更点清单最大条数 */
        private int maxChanges = 20;
        /** 单次简历优化整体超时（秒） */
        private int timeoutSeconds = 180;
    }

    @Data
    public static class Executor {
        private int corePoolSize = 4;
        private int maxPoolSize = 16;
        private int queueCapacity = 200;
        private int keepAliveSeconds = 60;
    }

    /**
     * LLM 调用线程池配置：隔离图执行线程池，避免图线程与 LLM 任务相互等待。
     * 合理并发数 ≈ LLM 配额(QPS) × 平均响应秒数（利特尔法则），按实际配额调优。
     */
    @Data
    public static class LlmExecutor {
        private int corePoolSize = 8;
        private int maxPoolSize = 32;
        private int queueCapacity = 400;
        private int keepAliveSeconds = 60;
    }

    @Data
    public static class Llm {
        private int timeoutSeconds = 60;
        private int maxRetries = 3;
        /** 结构化输出（generateJson）最大输出 token 数：JSON 节点输出短小，1024 足够；触顶即截断（finishReason=LENGTH） */
        private int maxOutputTokens = 1024;
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
    public static class Embedding {
        /** Ollama 服务地址 */
        private String baseUrl = "http://localhost:11434";
        /** 向量模型名（BGE-M3 输出 1024 维，与 pgvector 列维度一致） */
        private String modelName = "bge-m3";
    }

    /**
     * 知识治理配置：父子分块参数可配（长文档导入时先按标题/章节切父块整章上下文，
     * 父块内再按段落切子块嵌入），批量导入文档数上限防一次请求打爆嵌入服务。
     */
    @Data
    public static class Knowledge {
        /** 父块大小（字符）：按标题/章节切分的整章上下文，不嵌入；检索命中子块后取父块装配 */
        private int parentChunkSize = 2000;
        /** 子块大小（字符）：父块内按段落切分的最小检索单元，嵌入进向量库供检索 */
        private int chunkSize = 400;
        /** 相邻子块重叠字符数：保留跨块边界的句间语义 */
        private int chunkOverlap = 80;
        /** 批量导入单次请求最大文档数（超限拒绝，防嵌入服务被打爆） */
        private int batchMaxDocuments = 20;
        /** 单篇文档/知识块最大字符数（入口 token 拦截：超限拒绝导入，防超大内容打爆嵌入服务与检索预算） */
        private int maxDocumentChars = 50_000;
    }

    /**
     * 知识导入线程池配置：批量导入逐篇并行执行（切分 + 嵌入 + 落库都是耗时操作），
     * 与图执行/LLM 线程池隔离，避免批量导入抢占对话链路资源。
     */
    @Data
    public static class KnowledgeImport {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 100;
        private int keepAliveSeconds = 60;
        /** 单篇导入等待上限（秒）：嵌入慢/队列积压时超时即取消该篇，避免 HTTP 线程无限挂起 */
        private int timeoutSeconds = 120;
    }

    @Data
    public static class CircuitBreaker {
        private int failureThreshold = 5;
        private int cooldownSeconds = 60;
    }

    /**
     * 检索配置：父子分块装配时，多命中父块组拼接的总长上限（超出截断，防上下文膨胀撑爆生成窗口）。
     */
    @Data
    public static class Retrieval {
        /** 父子装配上下文预算（字符）：命中子块 → 父块整章（同 title 组拼接）的总长上限 */
        private int contextBudget = 2400;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int permitsPerMinute = 60;
        private boolean failOpen = true;
    }

    /**
     * 会话锁配置（显式租约，不使用 Watchdog 续期）。
     * 锁最迟在租约到期后自动释放，保证异常路径漏解锁时有界自愈；
     * 代价是不自动续期，租约必须大于最长图执行耗时。
     */
    @Data
    public static class Lock {
        /** 锁租约（秒）：必须大于 executionTimeoutSeconds 与 resume.timeoutSeconds，否则任务未结束锁先过期 */
        private int leaseSeconds = 300;
    }
}
