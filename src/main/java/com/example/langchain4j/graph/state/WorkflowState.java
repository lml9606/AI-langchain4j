package com.example.langchain4j.graph.state;

import com.example.langchain4j.common.constant.ChatOptions;
import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流状态：LangGraph4j 图内共享的状态容器。
 *
 * 更新机制：节点不直接改状态，而是返回 {@code Map<String,Object>} 增量更新 ——
 * 框架把返回值合并进状态后生成新状态对象传给下一个节点（不可变更新），
 * 每个节点看到的是“前序节点完成后”的快照，天然支持 checkpoint 回放与并发安全。
 * 继承框架的 {@link AgentState}，数据存放在其内部 Map 中。
 */
@SuppressWarnings({"removal"})
public class WorkflowState extends AgentState {

    // ---- 状态字段名常量：节点写入 Map 与访问器取值共用同一 key，避免字符串漂移 ----
    /** 用户原始输入（EntryNode 清洗后写入，作为全链路查询源头） */
    public static final String QUERY = "query";
    /** 改写后的自包含 query（图外 QueryRewriter 写入，未改写时为空；effectiveQuery 优先取它） */
    public static final String REWRITTEN_QUERY = "rewrittenQuery";
    /** 最近 N 轮历史原文（role/content 键值对列表，正序；服务层注入，生成节点以消息角色拼入 prompt） */
    public static final String HISTORY = "history";
    /** 当前话题段摘要（服务层注入，生成节点拼入 system prompt 回忆窗口外的历史要点） */
    public static final String SUMMARY = "summary";
    /** 本轮实际使用的模型名（入图时由服务层解析，节点调用 LLM 时按此路由） */
    public static final String MODEL = "model";
    /** 意图分类结果（IntentPlanNode 写入：code_generate/knowledge_qa/task/chitchat/reject） */
    public static final String INTENT = "intent";
    /** 意图判断依据（正常为 LLM 输出的分类理由；降级时为"模型降级"/"解析降级"） */
    public static final String INTENT_REASON = "intentReason";
    /** 检索到的知识上下文（RetrievalNode 写入，供生成节点引用；为空时路由转人工） */
    public static final String CONTEXT = "context";
    /** 本轮最终答案（Generate/Handoff/Fallback/Finalize 节点写入，服务层据此落库与响应） */
    public static final String RESULT = "result";
    /** Corrective RAG 重试计数（EntryNode 归零、ValidateNode 失败 +1，Router 据此判断回生成/回检索/兜底） */
    public static final String RETRY_COUNT = "retryCount";
    /** 质检状态子对象（ValidateNode 写入；Router 路由、GenerateNode 重试指令、RetrievalNode 重查模式读取） */
    public static final String VALIDATION = "validation";
    /** 本轮待检索查询列表（EntryNode 每轮种入默认单查询；IntentPlanNode 在知识问答意图下覆盖为规划子查询） */
    public static final String SUB_QUERIES = "subQueries";
    /** 旧版多跳状态子对象：仅为历史 checkpoint 兼容保留，新链路不再写入。 */
    public static final String HOP = "hop";
    /** 上下文不完整标记（完整检索后证据仍不充分时标记 true，生成端据此声明回答边界） */
    public static final String CONTEXT_INCOMPLETE = "contextIncomplete";
    /** 结构化查询规划（IntentPlanNode 写入：主查询/子查询/复杂度/风险/是否需要完整检索） */
    public static final String QUERY_PLAN = "queryPlan";
    /** 检索证据评估（探针/完整检索节点写入：命中数/最高分/关键词覆盖率/置信度/是否充分） */
    public static final String RETRIEVAL_EVIDENCE = "retrievalEvidence";
    /** 检索阶段（retrieval_probe / retrieval_full / requery；路由据此判断是否还需要升级检索） */
    public static final String RETRIEVAL_DEPTH = "retrievalDepth";
    /** 证据支撑置信度（ValidateNode 写入：回答是否被检索资料支撑） */
    public static final String GROUNDING_CONFIDENCE = "groundingConfidence";
    /** 最终置信度 = min(检索证据置信度, 证据支撑置信度)，供响应透出 */
    public static final String FINAL_CONFIDENCE = "finalConfidence";
    /** 检索根因标记（EntryNode 归零；写入点与取值见 ROOT_CAUSE_* 常量注释；透出到响应与审计表供用户区分与数据闭环） */
    public static final String ROOT_CAUSE = "rootCause";
    /** 根因取值：问题太复杂（并列复合问题，分解后子查询分别命中召回，用户问法本身没问题） */
    public static final String ROOT_CAUSE_COMPLEX_QUERY = "COMPLEX_QUERY";
    /** 根因取值：表述不当（首轮检索证据链不足或全空，换问法补查后命中，用户问法需更具体） */
    public static final String ROOT_CAUSE_EXPRESSION_ISSUE = "EXPRESSION_ISSUE";
    /** 根因取值：知识缺失（探针与完整检索均为空，知识库确实没有相关资料） */
    public static final String ROOT_CAUSE_KNOWLEDGE_MISSING = "KNOWLEDGE_MISSING";
    /** 错误标记（成功路径清空，Generate 失败写入原因；Router 发现非空即转兜底） */
    public static final String ERROR = "error";
    /** 兜底降级标记（FallbackNode 写入 true，透出到响应供调用方识别降级应答） */
    public static final String FALLBACK = "fallback";
    /** 转人工标记（HandoffNode 写入 true；服务层据此自动建工单，接入运营闭环） */
    public static final String HANDOFF = "handoff";
    /** 本轮生成消耗的 token 总数（GenerateNode 写入，模型未返回时为空；用于审计与成本统计） */
    public static final String TOKEN_USAGE = "tokenUsage";
    /** 流程完成标记（FinalizeNode 写入 true，透出到响应） */
    public static final String DONE = "done";
    /** 当前用户 ID（服务层入图时注入；需要按用户隔离数据的节点使用） */
    public static final String USER_ID = "userId";
    /** 当前会话 ID（服务层入图时注入；需要回写会话或审计记录的节点使用） */
    public static final String CONVERSATION_ID = "conversationId";
    /** 回答模式（服务层归一后入图：auto/deep；deep 放宽检索候选数并强制完整质检） */
    public static final String ANSWER_MODE = "answerMode";
    /** 检索可见范围（服务层归一后入图：all/private；private 仅检索本人库） */
    public static final String VISIBILITY = "visibility";
    /** 任务实体槽位（TaskPlanNode 写入：工具调用所需的业务实体槽位，缺失时为空值） */
    public static final String TASK_ENTITIES = "taskEntities";
    /** 任务缺失槽位（TaskPlanNode 写入：非空时本轮转为追问，不调用工具） */
    public static final String TASK_MISSING = "taskMissing";
    /** 任务执行计划（TaskPlanNode 写入：步骤列表，每步含 tool/args/purpose） */
    public static final String TASK_PLAN = "taskPlan";
    /** 工具调用轨迹（TaskExecuteNode 写入：tool/args/success/summary/errorCode/costMs） */
    public static final String TASK_TRACE = "taskTrace";
    /** 工具证据（TaskExecuteNode 写入：type/sourceId/title/snippet/score，供回答引用） */
    public static final String TASK_EVIDENCE = "taskEvidence";
    /** 本轮回答置信度（TaskAnswerNode 写入：0~1 启发式评分） */
    public static final String TASK_CONFIDENCE = "taskConfidence";
    /** 置信度说明（TaskAnswerNode 写入：证据条数/失败次数等可解释理由） */
    public static final String TASK_CONFIDENCE_REASON = "taskConfidenceReason";
    /** 追问标记（TaskPlanNode 缺参时写入 true，路由直接收尾，不执行工具） */
    public static final String TASK_CLARIFY = "taskClarify";
    /** 待确认写操作（TaskPlanNode 写入：tool/args/purpose/risk） */
    public static final String TASK_PENDING_ACTION = "taskPendingAction";
    /** 写操作待确认标记（TaskPlanNode 写入 true，路由转确认节点） */
    public static final String TASK_CONFIRMATION_REQUIRED = "taskConfirmationRequired";
    /** 确认令牌 ID（TaskConfirmNode 写入，响应透出给前端） */
    public static final String TASK_CONFIRMATION_ID = "taskConfirmationId";
    /** 本次执行是否已获用户确认（确认执行链路入参；普通规划阶段必须为 false） */
    public static final String TASK_APPROVED = "taskApproved";
    /** 写工具幂等键（确认执行链路入参，等于 confirmationId） */
    public static final String TASK_IDEMPOTENCY_KEY = "taskIdempotencyKey";
    /** 固定任务工作流 ID（TaskPlanNode 写入；确认链路据此复核工具契约） */
    public static final String TASK_WORKFLOW_ID = "taskWorkflowId";
    /** 任务运行 ID（TaskExecuteNode 写入；多步骤执行、确认和查询都以它为主键） */
    public static final String TASK_RUN_ID = "taskRunId";
    /** 任务运行状态（PLANNING/WAITING_CONFIRM/RUNNING/SUCCEEDED/FAILED 等，响应透出） */
    public static final String TASK_RUN_STATUS = "taskRunStatus";

    // ---- 旧版平铺字段 key：仅用于升级前 checkpoint 的迁移与兼容读取，新数据一律写入嵌套子对象 ----
    private static final String LEGACY_VALIDATED = "validated";
    private static final String LEGACY_VALIDATION_ISSUE = "validationIssue";
    private static final String LEGACY_VALIDATION_REASON = "validationReason";
    private static final String LEGACY_HOP_COUNT = "hopCount";
    private static final String LEGACY_PENDING_QUERY = "pendingQuery";
    private static final String LEGACY_HOP_SUFFICIENT = "hopSufficient";

    /**
     * 质检状态子对象（VALIDATION 嵌套值）：质检通过标记 + 失败类型 + 失败原因。
     *
     * 节点写入时使用 record 形态；checkpoint 反序列化后为 Map 形态，访问器统一防御转换。
     */
    public record Validation(boolean validated, String issue, String reason) {

        /** 子对象内部 key：与 JSON 序列化字段名一致（checkpoint 反序列化后按此从 Map 取值） */
        static final String KEY_VALIDATED = "validated";
        static final String KEY_ISSUE = "issue";
        static final String KEY_REASON = "reason";

        /** 工厂：null 归一为空串，保证访问器下游永远拿到安全值 */
        public static Validation of(boolean validated, String issue, String reason) {
            return new Validation(validated, issue == null ? "" : issue, reason == null ? "" : reason);
        }
    }

    /**
     * 多跳状态子对象（HOP 嵌套值）：已执行补查轮数（不含首轮）+ 下一跳检索查询 + 上下文判定标记。
     *
     * 节点写入时使用 record 形态；checkpoint 反序列化后为 Map 形态，访问器统一防御转换。
     */
    public record Hop(int count, String pendingQuery, boolean sufficient) {

        /** 子对象内部 key：与 JSON 序列化字段名一致（checkpoint 反序列化后按此从 Map 取值） */
        static final String KEY_COUNT = "count";
        static final String KEY_PENDING_QUERY = "pendingQuery";
        static final String KEY_SUFFICIENT = "sufficient";

        /** 工厂：null 归一为空串，保证访问器下游永远拿到安全值 */
        public static Hop of(int count, String pendingQuery, boolean sufficient) {
            return new Hop(count, pendingQuery == null ? "" : pendingQuery, sufficient);
        }
    }

    /**
     * 查询规划子对象：一次结构化调用同时产出意图判断与检索计划。
     *
     * requiresFullRetrieval 由复杂度/子查询数推导，不在路由层重复判断；模型不输出该字段，
     * 避免把“是否需要检索”交给模型自由裁量。
     */
    public record QueryPlan(String primaryQuery, List<String> subQueries, String complexity,
                            String risk, boolean requiresFullRetrieval, String reason) {

        static final String KEY_PRIMARY_QUERY = "primaryQuery";
        static final String KEY_SUB_QUERIES = "subQueries";
        static final String KEY_COMPLEXITY = "complexity";
        static final String KEY_RISK = "risk";
        static final String KEY_REQUIRES_FULL_RETRIEVAL = "requiresFullRetrieval";
        static final String KEY_REASON = "reason";

        public QueryPlan {
            primaryQuery = primaryQuery == null ? "" : primaryQuery;
            subQueries = subQueries == null ? List.of() : List.copyOf(subQueries);
            complexity = complexity == null ? "simple" : complexity;
            risk = risk == null ? "low" : risk;
            reason = reason == null ? "" : reason;
        }

        public static QueryPlan empty() {
            return new QueryPlan("", List.of(), "simple", "low", false, "");
        }
    }

    /**
     * 检索证据子对象：全部由检索结果与查询词覆盖计算，不采信模型自报置信度。
     * confidence 只用于分层质检与响应展示，阈值必须通过验证集校准。
     */
    public record RetrievalEvidence(int hitCount, double topScore, double keywordCoverage,
                                    double confidence, boolean sufficient, String reason) {

        static final String KEY_HIT_COUNT = "hitCount";
        static final String KEY_TOP_SCORE = "topScore";
        static final String KEY_KEYWORD_COVERAGE = "keywordCoverage";
        static final String KEY_CONFIDENCE = "confidence";
        static final String KEY_SUFFICIENT = "sufficient";
        static final String KEY_REASON = "reason";

        public RetrievalEvidence {
            hitCount = Math.max(0, hitCount);
            topScore = Math.max(0.0, topScore);
            keywordCoverage = Math.max(0.0, Math.min(1.0, keywordCoverage));
            confidence = Math.max(0.0, Math.min(1.0, confidence));
            reason = reason == null ? "" : reason;
        }

        public static RetrievalEvidence empty() {
            return new RetrievalEvidence(0, 0.0, 0.0, 0.0, false, "无检索结果");
        }
    }

    public WorkflowState(Map<String, Object> initData) {
        super(migrateLegacyFields(initData));
    }

    /**
     * 旧版平铺字段迁移：升级前的 checkpoint 反序列化后 Map 中含平铺字段（validated/hopCount 等），
     * 重建状态时提升为嵌套子对象并移除旧 key，保证落库与断点续跑都是新结构。
     *
     * 在父类拷贝 initData 之前执行（super 参数求值时机），因此迁移结果即内部存储内容；
     * 幂等：无旧字段时原样返回，不做多余拷贝。
     *
     * @param initData 待重建的原始状态数据（可能是不可变 Map，有旧字段时才拷贝新 Map 修改，不改原 Map）
     * @return 迁移后的状态数据（无旧字段时原样返回入参）
     */
    private static Map<String, Object> migrateLegacyFields(Map<String, Object> initData) {
        if (initData == null) {
            return Map.of();
        }
        // 步骤1：探测旧平铺字段是否存在（存在才需要迁移，避免每次构造多一次拷贝）
        boolean hasLegacyValidation = initData.containsKey(LEGACY_VALIDATED)
                || initData.containsKey(LEGACY_VALIDATION_ISSUE)
                || initData.containsKey(LEGACY_VALIDATION_REASON);
        boolean hasLegacyHop = initData.containsKey(LEGACY_HOP_COUNT)
                || initData.containsKey(LEGACY_PENDING_QUERY)
                || initData.containsKey(LEGACY_HOP_SUFFICIENT);
        if (!hasLegacyValidation && !hasLegacyHop) {
            return initData;
        }
        // 步骤2：拷贝后提升旧字段为嵌套子对象并移除旧 key（旧值经防御解析，容忍类型漂移）
        Map<String, Object> migrated = new LinkedHashMap<>(initData);
        if (hasLegacyValidation) {
            migrated.put(VALIDATION, Validation.of(
                    boolOf(migrated.remove(LEGACY_VALIDATED), false),
                    strOf(migrated.remove(LEGACY_VALIDATION_ISSUE)),
                    strOf(migrated.remove(LEGACY_VALIDATION_REASON))));
        }
        if (hasLegacyHop) {
            migrated.put(HOP, Hop.of(
                    intOf(migrated.remove(LEGACY_HOP_COUNT), 0),
                    strOf(migrated.remove(LEGACY_PENDING_QUERY)),
                    boolOf(migrated.remove(LEGACY_HOP_SUFFICIENT), false)));
        }
        return migrated;
    }

    /**
     * 每轮重置的跨轮计数字段：EntryNode 在每轮入口调用（与自身更新合并返回），
     * 归零 retryCount/hop 子对象/rootCause，防断点恢复重入时上一轮残留影响本轮。
     *
     * hop 子对象整体归零（兼容历史 checkpoint；新链路不再使用多跳判断）。
     * 新增跨轮计数字段时必须同步补充到此处 —— EntryNode 只需引用本方法，无需逐字段修改。
     *
     * @return 归零增量（不可变 Map，调用方合并进自身更新）
     */
    public static Map<String, Object> perTurnReset() {
        // 步骤1：Map.of 最多 10 组键值，任务链路字段已超过该上限，改用可变 Map 逐项写入
        Map<String, Object> reset = new LinkedHashMap<>();
        // 步骤2：原有跨轮计数字段归零
        reset.put(RETRY_COUNT, 0);
        reset.put(HOP, Hop.of(0, "", false));
        reset.put(ROOT_CAUSE, "");
        // 证据优先链路每轮重置：规划、探针证据、检索阶段、上下文完整性与置信度不能跨轮残留
        reset.put(QUERY_PLAN, QueryPlan.empty());
        reset.put(RETRIEVAL_EVIDENCE, RetrievalEvidence.empty());
        reset.put(RETRIEVAL_DEPTH, "");
        reset.put(CONTEXT_INCOMPLETE, false);
        reset.put(GROUNDING_CONFIDENCE, 0.0);
        reset.put(FINAL_CONFIDENCE, 0.0);
        // 步骤3：任务链路字段归零（防止断点恢复重入时残留上一轮计划/证据）
        reset.put(TASK_ENTITIES, Map.of());
        reset.put(TASK_MISSING, List.of());
        reset.put(TASK_PLAN, List.of());
        reset.put(TASK_TRACE, List.of());
        reset.put(TASK_EVIDENCE, List.of());
        reset.put(TASK_CONFIDENCE, 0.0);
        reset.put(TASK_CONFIDENCE_REASON, "");
        reset.put(TASK_CLARIFY, false);
        reset.put(TASK_PENDING_ACTION, Map.of());
        reset.put(TASK_CONFIRMATION_REQUIRED, false);
        reset.put(TASK_CONFIRMATION_ID, "");
        reset.put(TASK_APPROVED, false);
        reset.put(TASK_IDEMPOTENCY_KEY, "");
        reset.put(TASK_WORKFLOW_ID, "");
        return reset;
    }

    // ---- 类型安全的访问器（带默认值，防御 null） ----
    //
    // 为什么不用字段直接取值：状态底层是 Map<String,Object>，任何节点都可能写错类型
    // 或漏写 key；访问器统一做类型转换 + 默认值兜底，节点代码永远拿到安全值。
    // retryCount/tokenUsage 等还需防御“数字/布尔被写成字符串”的场景
    // （如 checkpoint 反序列化后类型漂移）：字符串能解析则解析，失败才回退默认值。

    public String query() {
        return value(QUERY, "");
    }

    /** 改写后的自包含 query（图外 QueryRewriter 写入，未改写时为空） */
    public String rewrittenQuery() {
        return value(REWRITTEN_QUERY, "");
    }

    /**
     * 有效查询：改写句优先，为空退回原始 query。
     * 意图识别、检索、生成统一取这个字段，节点不再自行写兜底逻辑。
     */
    public String effectiveQuery() {
        String rewritten = rewrittenQuery();
        return rewritten.isBlank() ? query() : rewritten;
    }

    /**
     * 最近 N 轮历史原文（role/content 键值对，正序），供生成节点以消息角色注入。
     * 防御 checkpoint 反序列化后的类型漂移：只接受 List 且逐项转成字符串键值对。
     */
    @SuppressWarnings("unchecked")
    public List<Map<String, String>> history() {
        Object v = value(HISTORY).orElse(null);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, String>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, String> entry = new LinkedHashMap<>(m.size());
                m.forEach((k, val) -> entry.put(String.valueOf(k), String.valueOf(val)));
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * 当前话题段摘要（服务层注入，供生成节点拼入 system prompt 回忆窗口外的历史要点）。
     * 命中历史话题段时为该段摘要；新话题/无摘要时为空串。
     */
    public String summary() {
        return value(SUMMARY, "");
    }

    /** 本轮实际使用的模型名（入图时由服务层解析，节点调用 LLM 时按此路由） */
    public String model() {
        return value(MODEL, "");
    }

    public String intent() {
        return value(INTENT, "");
    }

    public String context() {
        return value(CONTEXT, "");
    }

    public String result() {
        return value(RESULT, "");
    }

    public int retryCount() {
        // 步骤1：取出原始值（缺省 0）
        Object v = value(RETRY_COUNT, 0);
        // 步骤2：数字直接取值；字符串漂移（checkpoint 反序列化）尝试解析
        if (v instanceof Number number) {
            return number.intValue();
        }
        // 步骤3：解析失败（脏数据）才回退 0
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    /**
     * 质检状态子对象：优先读嵌套对象；防御 checkpoint 反序列化后的 Map 形态；
     * 旧版平铺字段回退（构造迁移已处理绝大多数路径，此处双保险）。
     */
    public Validation validation() {
        Object v = value(VALIDATION).orElse(null);
        // 步骤1：内存态（节点刚写入的 record）直接返回
        if (v instanceof Validation validation) {
            return validation;
        }
        // 步骤2：checkpoint 反序列化后 JSON 对象还原为 Map，逐 key 防御解析
        if (v instanceof Map<?, ?> m) {
            return Validation.of(
                    boolOf(m.get(Validation.KEY_VALIDATED), false),
                    strOf(m.get(Validation.KEY_ISSUE)),
                    strOf(m.get(Validation.KEY_REASON)));
        }
        // 步骤3：旧版平铺字段回退（升级前 checkpoint，未走构造迁移的路径）
        return Validation.of(
                boolOf(value(LEGACY_VALIDATED).orElse(null), false),
                strOf(value(LEGACY_VALIDATION_ISSUE).orElse(null)),
                strOf(value(LEGACY_VALIDATION_REASON).orElse(null)));
    }

    /**
     * 本轮待检索查询列表（由 intent_plan 产出主查询/子查询；旧 checkpoint 可能为空）。
     * 仅旧 checkpoint（改造前无种入）可能为空，检索端已防御回退为原查询单查。
     * 防御 checkpoint 反序列化后的类型漂移：只接受 List 且逐项转字符串、过滤空白。
     */
    @SuppressWarnings("unchecked")
    public List<String> subQueries() {
        Object v = value(SUB_QUERIES).orElse(null);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /**
     * 多跳状态子对象：优先读嵌套对象；防御 checkpoint 反序列化后的 Map 形态；
     * 旧版平铺字段回退（构造迁移已处理绝大多数路径，此处双保险）。
     */
    public Hop hop() {
        Object v = value(HOP).orElse(null);
        // 步骤1：内存态（节点刚写入的 record）直接返回
        if (v instanceof Hop hop) {
            return hop;
        }
        // 步骤2：checkpoint 反序列化后 JSON 对象还原为 Map，逐 key 防御解析
        if (v instanceof Map<?, ?> m) {
            return Hop.of(
                    intOf(m.get(Hop.KEY_COUNT), 0),
                    strOf(m.get(Hop.KEY_PENDING_QUERY)),
                    boolOf(m.get(Hop.KEY_SUFFICIENT), false));
        }
        // 步骤3：旧版平铺字段回退（升级前 checkpoint，未走构造迁移的路径）
        return Hop.of(
                intOf(value(LEGACY_HOP_COUNT).orElse(null), 0),
                strOf(value(LEGACY_PENDING_QUERY).orElse(null)),
                boolOf(value(LEGACY_HOP_SUFFICIENT).orElse(null), false));
    }

    /** 本轮结构化查询规划；缺失时返回默认单查询计划。 */
    public QueryPlan queryPlan() {
        Object v = value(QUERY_PLAN).orElse(null);
        if (v instanceof QueryPlan plan) {
            return plan;
        }
        if (v instanceof Map<?, ?> m) {
            String primary = strOf(m.get(QueryPlan.KEY_PRIMARY_QUERY));
            return new QueryPlan(
                    primary,
                    stringsOf(m.get(QueryPlan.KEY_SUB_QUERIES)),
                    strOf(m.get(QueryPlan.KEY_COMPLEXITY)),
                    strOf(m.get(QueryPlan.KEY_RISK)),
                    boolOf(m.get(QueryPlan.KEY_REQUIRES_FULL_RETRIEVAL), false),
                    strOf(m.get(QueryPlan.KEY_REASON)));
        }
        return QueryPlan.empty();
    }

    /** 本轮检索证据评估；缺失时返回空证据。 */
    public RetrievalEvidence retrievalEvidence() {
        Object v = value(RETRIEVAL_EVIDENCE).orElse(null);
        if (v instanceof RetrievalEvidence evidence) {
            return evidence;
        }
        if (v instanceof Map<?, ?> m) {
            return new RetrievalEvidence(
                    intOf(m.get(RetrievalEvidence.KEY_HIT_COUNT), 0),
                    doubleOf(m.get(RetrievalEvidence.KEY_TOP_SCORE), 0.0),
                    doubleOf(m.get(RetrievalEvidence.KEY_KEYWORD_COVERAGE), 0.0),
                    doubleOf(m.get(RetrievalEvidence.KEY_CONFIDENCE), 0.0),
                    boolOf(m.get(RetrievalEvidence.KEY_SUFFICIENT), false),
                    strOf(m.get(RetrievalEvidence.KEY_REASON)));
        }
        return RetrievalEvidence.empty();
    }

    /** 检索阶段：retrieval_probe / retrieval_full / requery；缺省空串。 */
    public String retrievalDepth() {
        return value(RETRIEVAL_DEPTH, "");
    }

    public double groundingConfidence() {
        return doubleOf(value(GROUNDING_CONFIDENCE).orElse(null), 0.0);
    }

    public double finalConfidence() {
        return doubleOf(value(FINAL_CONFIDENCE).orElse(null), 0.0);
    }

    /** 上下文不完整标记（证据不足时标记 true，生成端据此声明信息可能不全）；缺省 false */
    public boolean contextIncomplete() {
        // 步骤1：取出原始值（缺省 false）
        // 步骤2：统一转为字符串后解析布尔，防御类型漂移
        return Boolean.parseBoolean(String.valueOf(value(CONTEXT_INCOMPLETE).orElse(false)));
    }

    /** 检索根因（ROOT_CAUSE_* 常量之一）；正常命中无根因时为空串 */
    public String rootCause() {
        return value(ROOT_CAUSE, "");
    }

    public String error() {
        return value(ERROR, "");
    }

    public boolean fallback() {
        // 步骤1：取出原始值（缺省 false）
        // 步骤2：统一转为字符串后解析布尔，防御类型漂移
        return Boolean.parseBoolean(String.valueOf(value(FALLBACK).orElse(false)));
    }

    /** 是否转人工（HandoffNode 写入 true；服务层据此自动建工单） */
    public boolean handoff() {
        // 步骤1：取出原始值（缺省 false）
        // 步骤2：统一转为字符串后解析布尔，防御类型漂移
        return Boolean.parseBoolean(String.valueOf(value(HANDOFF).orElse(false)));
    }

    public Integer tokenUsage() {
        // 步骤1：取出原始值（可为空）
        Object v = value(TOKEN_USAGE).orElse(null);
        // 步骤2：防御类型漂移 —— 非数字一律返回 null
        return v instanceof Number number ? number.intValue() : null;
    }

    public boolean done() {
        // 步骤1：取出原始值（缺省 false）
        // 步骤2：统一转为字符串后解析布尔，防御类型漂移
        return Boolean.parseBoolean(String.valueOf(value(DONE).orElse(false)));
    }

    /** 当前用户 ID（服务层入图时注入）；缺省空串 */
    public String userId() {
        return value(USER_ID, "");
    }

    /** 当前会话 ID（服务层入图时注入）；缺省空串 */
    public String conversationId() {
        return value(CONVERSATION_ID, "");
    }

    /** 回答模式：服务层归一后注入，非法值不会出现在图内；缺省 auto（旧 checkpoint 兼容） */
    public String answerMode() {
        String mode = value(ANSWER_MODE, ChatOptions.ANSWER_MODE_AUTO);
        return ChatOptions.normalizeAnswerMode(mode, ChatOptions.ANSWER_MODE_AUTO);
    }

    /** 检索可见范围：服务层归一后注入；缺省 all（旧 checkpoint 兼容） */
    public String visibility() {
        String scope = value(VISIBILITY, ChatOptions.VISIBILITY_ALL);
        return ChatOptions.normalizeVisibility(scope, ChatOptions.VISIBILITY_ALL);
    }

    /** 任务实体槽位：checkpoint 反序列化后可能为 Map，统一转成字符串键值对 */
    public Map<String, String> taskEntities() {
        Object v = value(TASK_ENTITIES).orElse(null);
        if (!(v instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, String> result = new LinkedHashMap<>(m.size());
        m.forEach((k, val) -> result.put(String.valueOf(k), strOf(val)));
        return result;
    }

    /** 任务缺失槽位：只接受 List，逐项转字符串并过滤空白 */
    public List<String> taskMissingSlots() {
        return stringList(TASK_MISSING);
    }

    /** 任务执行计划：只接受 List<Map>，逐项转字符串键值对（保留嵌套 args 对象） */
    public List<Map<String, Object>> taskPlan() {
        return mapList(TASK_PLAN);
    }

    /** 当前任务的运行 ID；没有进入多步骤执行时为空字符串。 */
    public String taskRunId() {
        return value(TASK_RUN_ID).map(String::valueOf).orElse("");
    }

    /** 当前任务的运行状态；没有进入多步骤执行时为空字符串。 */
    public String taskRunStatus() {
        return value(TASK_RUN_STATUS).map(String::valueOf).orElse("");
    }

    /** 工具调用轨迹（结构同计划步骤，额外含 success/summary/errorCode/costMs） */
    public List<Map<String, Object>> taskTrace() {
        return mapList(TASK_TRACE);
    }

    /** 工具证据（引用来源，供响应透出与报告统计） */
    public List<Map<String, Object>> taskEvidence() {
        return mapList(TASK_EVIDENCE);
    }

    /** 本轮是否因缺参转为追问 */
    public boolean taskClarify() {
        return Boolean.parseBoolean(String.valueOf(value(TASK_CLARIFY).orElse(false)));
    }

    /** 待确认写操作：只接受 Map，类型漂移时返回空 Map */
    public Map<String, Object> taskPendingAction() {
        Object v = value(TASK_PENDING_ACTION).orElse(null);
        if (!(v instanceof Map<?, ?> m)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(m.size());
        m.forEach((k, val) -> result.put(String.valueOf(k), val));
        return result;
    }

    /** 是否需要用户确认写操作 */
    public boolean taskConfirmationRequired() {
        return Boolean.parseBoolean(String.valueOf(value(TASK_CONFIRMATION_REQUIRED).orElse(false)));
    }

    /** 确认令牌 ID；缺省空串 */
    public String taskConfirmationId() {
        return value(TASK_CONFIRMATION_ID, "");
    }

    /** 本次执行是否已获确认（确认执行链路入参） */
    public boolean taskApproved() {
        return Boolean.parseBoolean(String.valueOf(value(TASK_APPROVED).orElse(false)));
    }

    /** 写工具幂等键；缺省空串 */
    public String taskIdempotencyKey() {
        return value(TASK_IDEMPOTENCY_KEY, "");
    }

    /** 固定任务工作流 ID；缺省空串 */
    public String taskWorkflowId() {
        return value(TASK_WORKFLOW_ID, "");
    }

    /** 本轮回答置信度（0~1）；缺省 0 */
    public double taskConfidence() {
        Object v = value(TASK_CONFIDENCE).orElse(null);
        if (v instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException ignored) {
            return 0.0;
        }
    }

    /** 置信度说明；缺省空串 */
    public String taskConfidenceReason() {
        return value(TASK_CONFIDENCE_REASON, "");
    }

    /** 通用字符串列表读取：容忍 checkpoint 反序列化后的类型漂移 */
    private List<String> stringList(String key) {
        Object v = value(key).orElse(null);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    /** 通用 Map 列表读取：只保留 Map 元素，键统一转字符串（嵌套值保持原样） */
    private List<Map<String, Object>> mapList(String key) {
        Object v = value(key).orElse(null);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> m) {
                Map<String, Object> entry = new LinkedHashMap<>(m.size());
                m.forEach((k, val) -> entry.put(String.valueOf(k), val));
                result.add(entry);
            }
        }
        return result;
    }

    // ---- 嵌套子对象取值防御工具（与访问器同哲学：容忍 checkpoint 反序列化后的类型漂移） ----

    /** 布尔解析：布尔直接取；其余形态统一转字符串解析（兼容 "true"/1 等写法）；null/解析失败回默认 */
    private static boolean boolOf(Object v, boolean def) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v == null ? def : Boolean.parseBoolean(String.valueOf(v).trim());
    }

    /** 字符串归一：null 归为空串（与访问器“缺省空串”约定一致） */
    private static String strOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }

    /** 整数解析：数字直接取；字符串漂移（checkpoint 反序列化）尝试解析；解析失败回默认 */
    private static int intOf(Object v, int def) {
        if (v instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    /** 浮点解析：数字直接取；字符串漂移尝试解析；失败回默认。 */
    private static double doubleOf(Object v, double def) {
        if (v instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(v).trim());
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    /** 字符串列表归一：过滤 null/空白，兼容 checkpoint 反序列化后的 List 形态。 */
    private static List<String> stringsOf(Object v) {
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            String text = strOf(item).trim();
            if (!text.isEmpty()) {
                result.add(text);
            }
        }
        return result;
    }
}
