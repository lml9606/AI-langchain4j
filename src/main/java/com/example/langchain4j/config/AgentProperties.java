package com.example.langchain4j.config;

import com.example.langchain4j.common.constant.ChatOptions;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

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

    /** 审计异步落库线程池配置（与主链路隔离，队列满可丢弃） */
    private AuditExecutor auditExecutor = new AuditExecutor();

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

    /** 多步骤任务执行配置 */
    private Task task = new Task();

    /** 校验重试上限（图内重试环次数） */
    private int maxValidateRetries = 3;

    /** 查询规划子查询上限（IntentPlanNode 白名单校验，超限只取前 N 个） */
    private int maxSubQueries = 3;

    /** 多轮上下文（短期记忆）配置 */
    private History history = new History();

    /** 工具运行时配置（只读/写工具白名单、超时、确认与执行线程池） */
    private Tool tool = new Tool();

    /** 单次图执行整体超时（秒）；超时即中止本轮：取消后台任务并返回降级应答，一问一答照常闭环，用户可直接重发 */
    private int executionTimeoutSeconds = 240;

    /** 确认写链路整体超时（秒）：超时返回“执行中”，令牌不消费，由任务台账和恢复 worker 收敛 */
    private int confirmTimeoutSeconds = 180;

    /** 跨节点通用运行上限：输入/输出/审计/候选集等，避免散落在各节点的魔法值 */
    private Limits limits = new Limits();

    /** 对话请求默认选项：客户端未传或传非法值时使用 */
    private RequestOptions request = new RequestOptions();

    @Data
    public static class RequestOptions {
        /** 默认回答模式：auto（标准链路）/ deep（深度链路） */
        private String defaultAnswerMode = ChatOptions.ANSWER_MODE_AUTO;
        /** 默认检索范围：all（公共+本人）/ private（仅本人） */
        private String defaultVisibility = ChatOptions.VISIBILITY_ALL;
    }

    @Data
    public static class Limits {
        /** 入口单条输入最大字符数（与 Controller 校验保持一致） */
        private int maxQueryLength = 4000;
        /** 检索查询单条最大字符数（意图规划输出防御） */
        private int maxRetrievalQueryLength = 400;
        /** 质检认为“过短”的回答最小字符数 */
        private int minResultLength = 5;
        /** 质检失败原因写回状态时的最大字符数 */
        private int maxValidationReasonLength = 200;
        /** 生成视图候选消息条数上限 */
        private int generationCandidateLimit = 40;
        /** assistant 检索上下文快照截断上限（仅排查取证） */
        private int contextSnapshotMax = 1200;
        /** 会话标题最大字符数 */
        private int conversationTitleMaxLength = 30;
        /** 会话历史默认返回条数 */
        private int defaultMessageLimit = 50;
        /** 会话历史最大返回条数 */
        private int maxMessageLimit = 200;
        /** 任务回答最多展示的证据条数 */
        private int taskAnswerMaxEvidence = 5;
        /** 任务回答证据片段最大字符数 */
        private int taskAnswerMaxSnippet = 300;
        /** 任务执行最多保留的轨迹条数 */
        private int taskTraceMaxEntries = 8;
        /** 任务执行最多保留的证据条数 */
        private int taskEvidenceMaxEntries = 8;
        /** 任务轨迹/证据摘要最大字符数 */
        private int taskSummaryMaxLength = 500;
        /** 审计 JSON 单字段最大字符数 */
        private int auditJsonMaxLength = 4000;
        /** 节点/方法审计单个字段最大字符数（防止每个节点重复存整段上下文） */
        private int auditNodeValueMaxLength = 400;
        /** 改写结果相对原句允许的额外字符数（防模型输出解释废话） */
        private int rewriteOutputSlack = 200;
        /** 工单列表默认返回条数 */
        private int defaultTicketLimit = 50;
        /** 工单列表最大返回条数 */
        private int maxTicketLimit = 200;
    }

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
        /** 摘要输入单条消息截断长度 */
        private int summaryMaxInputItemLength = 2000;
        /** 单次摘要任务最多批次数（防异常循环） */
        private int summaryMaxTaskLoops = 3;
        /** 摘要输出最短长度（低于视为异常输出） */
        private int summaryMinRefinedLength = 50;
        /** 摘要缩水比例：低于已有摘要的 1/N 视为异常 */
        private int summaryMinRetainRatio = 3;
        /** 段摘要专用模型；为空时使用 agent.llm.default-model，便于摘要走低成本模型 */
        private String summaryModel = "";
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

    /** 审计线程池：单/低并发批量落库，队列满丢弃审计而不阻塞业务。 */
    @Data
    public static class AuditExecutor {
        private int corePoolSize = 1;
        private int maxPoolSize = 2;
        private int queueCapacity = 1000;
        private int keepAliveSeconds = 60;
    }

    @Data
    public static class Llm {
        private int timeoutSeconds = 60;
        private int maxRetries = 3;
        /** 单模型每分钟最多调用次数（Redis 固定窗口，多实例共享；<=0 关闭模型级限流） */
        private int maxCallsPerMinute = 120;
        /** 重试退避基数（毫秒）：第 N 次退避 = base * 2^N */
        private long backoffBaseMs = 1000L;
        /** 重试退避随机抖动上限（毫秒），避免多实例同时重试 */
        private long backoffJitterMs = 500L;
        /** 结构化输出（generateJson）最大输出 token 数：JSON 节点输出短小，1024 足够；触顶即截断（finishReason=LENGTH） */
        private int maxOutputTokens = 1024;
        /** 自然语言回答预算：按意图/复杂度/模式动态选择字数与输出 token 上限 */
        private AnswerBudget answerBudget = new AnswerBudget();
        /** 默认模型名：前端不传 model 参数时使用；必须是注册表已注册的模型 */
        private String defaultModel = "deepseek-flash";
        /** 千问模型名（与 langchain4j.community.dashscope.chat-model.model-name 保持一致） */
        private String qwenModelName = "qwen-max";
        private CircuitBreaker circuitBreaker = new CircuitBreaker();
    }

    /** 生成回答预算：maxChars=0 表示不追加字数提示，只使用 maxOutputTokens 硬上限。 */
    @Data
    public static class AnswerBudget {
        private Profile chitchat = new Profile(120, 256);
        private Profile knowledgeSimple = new Profile(500, 1024);
        private Profile knowledgeCompound = new Profile(900, 1536);
        private Profile knowledgeDeep = new Profile(2800, 4096);
        private Profile code = new Profile(0, 6144);
    }

    /** 单个回答预算档位：maxChars 控制提示词目标长度，maxOutputTokens 是供应商侧硬上限。 */
    @Data
    public static class Profile {
        private int maxChars;
        private int maxOutputTokens;

        public Profile() {
        }

        public Profile(int maxChars, int maxOutputTokens) {
            this.maxChars = maxChars;
            this.maxOutputTokens = maxOutputTokens;
        }
    }

    @Data
    public static class Deepseek {
        /** DeepSeek 官方 API Key（环境变量 DEEPSEEK_API_KEY 注入，避免入库） */
        private String apiKey = "";
        /** OpenAI 兼容协议接入地址 */
        private String baseUrl = "https://api.deepseek.com";
        /**
         * 支持的模型名列表（逗号分隔）：每个模型注册一个独立实例，共用同一 apiKey/baseUrl；
         * 新增模型只需在列表加一个名字（模型名以 DeepSeek 官方 API 为准），零代码改动。
         * 列表必须包含 agent.llm.default-model，否则启动期 ModelRegistry 校验 fail-fast。
         */
        private List<String> modelNames = List.of("deepseek-flash");
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
        /**
         * 父块硬上限（字符）：任何父块都不得超过该值；超长章节会继续按软标题/段落/句子续切。
         * 与 targetMax 不同，这是硬约束，不是选层目标。
         */
        private int parentChunkSize = 2000;
        /**
         * 父块选层目标下限（字符）：统计某一层所有标题的整棵子树长度，中位数低于该值时该层不直接合格。
         * 它不是父块的最小硬长度，小章节不会为了达到该值而强制合并。
         */
        private int parentTargetMin = 800;
        /**
         * 父块选层目标上限（字符）：与 parentTargetMin 共同决定父块标题层级，避免写死 H3。
         * 选层时从 H2 向更深层尝试，优先保留上下文更完整的浅层父块。
         */
        private int parentTargetMax = 2000;
        /** 子块大小（字符）：父块内按段落切分的最小检索单元，嵌入进向量库供检索 */
        private int chunkSize = 400;
        /** 相邻子块重叠字符数：保留跨块边界的句间语义 */
        private int chunkOverlap = 80;
        /** 子块 token 估算上限：与 chunkSize 双约束，防止长英文/代码/密排中文超出嵌入模型预算 */
        private int chunkTokenLimit = 512;
        /** 批量导入单次请求最大文档数（超限拒绝，防嵌入服务被打爆） */
        private int batchMaxDocuments = 20;
        /** 单篇文档/知识块最大字符数（入口 token 拦截：超限拒绝导入，防超大内容打爆嵌入服务与检索预算） */
        private int maxDocumentChars = 50_000;

        /** 旧版本保留小时数：发布后保留多久供回滚/对比，到期由 GC 回收 */
        private int versionRetentionHours = 24;
        /** 旧版本回收开关（关闭后数据继续累积，仅用于排障或手动运维） */
        private boolean gcEnabled = true;
        /** GC 单轮最多扫描的文档数：限制任务时长，避免一次扫全库 */
        private int gcMaxDocuments = 50;
        /** GC 单文档单批删除行数：分批删除，避免一次删上万行形成长事务与大锁 */
        private int gcBatchSize = 500;
        /** 导入中断判定分钟数：超过该时长仍为 IMPORTING，视为进程异常退出，允许后续导入接管 */
        private int importStaleMinutes = 30;
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
        /** 失败计数窗口（秒）：窗口内累计达到阈值才熔断，避免偶发失败永久累积 */
        private int failureWindowSeconds = 60;
    }

    /**
     * 检索配置：父子分块装配时，多命中父块组拼接的总长上限（超出截断，防上下文膨胀撑爆生成窗口）。
     */
    @Data
    public static class Retrieval {
        /** 未显式传 topK 时的默认召回条数 */
        private int defaultTopK = 3;

        /** 稠密通道最小相似度（1-余弦距离）：低于该值视为不相关；需按验证集校准 */
        private double denseMinScore = 0.85;

        /** 稀疏通道最小内积（已归一化）：稀疏分数天然低于稠密，默认只过滤零重叠噪声 */
        private double sparseMinScore = 0.05;

        /** RRF 融合常数：经典取值 60，rank 从 1 起 */
        private int rrfK = 60;

        /** 单路宽召回候选池大小：先宽召回再融合截断 */
        private int fusionPool = 10;

        /** 父子装配上下文预算（字符）：命中子块 → 父块（同 title 组拼接）的总长上限 */
        private int contextBudget = 5000;
        /** 单次检索最多装配的父块组数：防止一个主题占满上下文，给多主题召回留预算 */
        private int maxParents = 3;
        /** 单个父块组的预算上限（字符）：0 表示不单独限制，仅受 contextBudget 约束 */
        private int parentContextBudget = 2000;
        /** 轻量探针检索候选数：只用于判断证据是否充分，不直接承担完整召回 */
        private int probeTopK = 3;
        /** 完整检索候选数（auto）：证据不足/复杂问题时升级 */
        private int fullTopK = 6;
        /** 完整检索候选数（deep）：复杂问题允许更宽召回 */
        private int deepTopK = 8;
        /** 复合问题单个子查询的上下文预算：每个子问题公平拿到一份，避免第一个子问题吃掉全部上下文 */
        private int compoundPerQueryContextBudget = 2200;
        /** 复合问题合并后的总上下文预算：高于所有子查询预算之和时按实际内容返回，低于时做最后截断保护 */
        private int compoundContextBudget = 8000;
        /** 证据充分最低置信度：需用验证集校准，不建议盲目下调 */
        private double minEvidenceConfidence = 0.45;
        /** 查询词最低覆盖率：防止仅靠一条弱命中就跳过完整检索 */
        private double minKeywordCoverage = 0.20;
        /** RRF 最高分归一化尺度：两通道 rank1 约为 2/(60+1)，用于折算 0~1 分数信号 */
        private double rrfScoreScale = 0.033;
        /** 回答关键结论引用比例低于该值时升级 LLM 质检（按验证集校准） */
        private double minAnswerSupport = 0.12;
    }

    @Data
    public static class RateLimit {
        private boolean enabled = true;
        private int permitsPerMinute = 60;
        private boolean failOpen = true;
    }

    /**
     * 会话锁配置。
     * chat/resume 使用宽松模式：显式租约、Redis 故障 fail-open；
     * confirm 使用严格模式：Redisson Watchdog 自动续期、Redis 故障 fail-closed。
     * leaseSeconds 仅作用于宽松模式，必须大于最长图执行耗时。
     */
    @Data
    public static class Lock {
        /** 宽松模式锁租约（秒）：必须大于 executionTimeoutSeconds；严格模式由 Watchdog 续期 */
        private int leaseSeconds = 300;
    }

    /**
     * 工具运行时全局配置。
     *
     * <p>工具自身的能力、写风险、超时和限流在 ToolDefinition 中声明；这里只保留
     * 全局应急开关与默认预算，避免同一策略分散在配置文件和数据库两处。
     */
    @Data
    public static class Tool {
        /** 全局写工具应急开关：false 时任何写工具都不能规划或执行（默认关闭） */
        private boolean writeEnabled = false;
        /** 工具默认超时（毫秒），工具定义未显式配置时使用 */
        private int timeoutMs = 3000;
        /** 单用户单工具每分钟调用上限（Redis 固定窗口，多实例共享计数） */
        private int maxCallsPerMinute = 20;
        /** 单个任务最多执行计划步骤数（防模型输出超长计划导致成本失控） */
        private int maxStepsPerTask = 10;
        /** 工具执行线程池 */
        private ExecutorConfig executor = new ExecutorConfig();
        /** 写操作人工确认配置 */
        private Confirmation confirmation = new Confirmation();

        @Data
        public static class ExecutorConfig {
            private int corePoolSize = 4;
            private int maxPoolSize = 8;
            private int queueCapacity = 100;
            private int keepAliveSeconds = 60;
        }

        @Data
        public static class Confirmation {
            /** 确认令牌有效期（秒）：过期后必须重新规划，避免陈旧操作被执行 */
            private int ttlSeconds = 300;
            /** 执行台账失联判定（秒）：RUNNING 超过该时长视为进程崩溃，允许接管重试 */
            private int staleSeconds = 60;
        }
    }

    /**
     * 多步骤任务配置：任务/步骤表是执行事实源，线程池只负责同阶段只读步骤并行。
     */
    @Data
    public static class Task {
        /** 单个任务允许的最大写步骤数（批量确认上限） */
        private int maxWritesPerTask = 5;
        /** 执行租约时长（秒）：进程崩溃后超过该时长允许其他实例接管 */
        private int leaseSeconds = 120;
        /** 同阶段只读步骤是否允许并行执行 */
        private boolean parallelReads = true;
        /** 崩溃恢复 worker 配置 */
        private Recovery recovery = new Recovery();

        @Data
        public static class Recovery {
            /** 是否开启租约过期任务恢复 */
            private boolean enabled = true;
            /** 扫描间隔（毫秒） */
            private long intervalMs = 30000;
            /** 每轮最多恢复任务数 */
            private int batchSize = 10;
        }
    }
}
