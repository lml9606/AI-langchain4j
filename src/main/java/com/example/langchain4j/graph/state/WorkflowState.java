package com.example.langchain4j.graph.state;

import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 工作流状态：LangGraph4j 图内共享的状态容器。
 * <p>
 * 更新机制：节点不直接改状态，而是返回 {@code Map<String,Object>} 增量更新 ——
 * 框架把返回值合并进状态后生成新状态对象传给下一个节点（不可变更新），
 * 每个节点看到的是“前序节点完成后”的快照，天然支持 checkpoint 回放与并发安全。
 * 继承框架的 {@link AgentState}，数据存放在其内部 Map 中。
 */
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
    /** 意图分类结果（IntentNode 写入：code_generate/knowledge_qa/reject；LLM 失败降级为 knowledge_qa） */
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
    /** 本轮待检索查询列表（EntryNode 每轮种入默认单查询；DecomposeNode 在知识问答意图下覆盖为分解结果；code_generate 跳过分解保持默认） */
    public static final String SUB_QUERIES = "subQueries";
    /** 多跳状态子对象（HopCheckNode 写入；Router 路由、RetrievalNode 多跳模式读取；EntryNode 每轮归零） */
    public static final String HOP = "hop";
    /** 上下文不完整标记（多跳超限仍信息不足时标记 true，生成端据此在 prompt 中声明信息可能不全） */
    public static final String CONTEXT_INCOMPLETE = "contextIncomplete";
    /** 检索根因标记（EntryNode 归零；写入点与取值见 ROOT_CAUSE_* 常量注释；透出到响应与审计表供用户区分与数据闭环） */
    public static final String ROOT_CAUSE = "rootCause";
    /** 根因取值：问题太复杂（并列复合问题，分解后子查询分别命中召回，用户问法本身没问题） */
    public static final String ROOT_CAUSE_COMPLEX_QUERY = "COMPLEX_QUERY";
    /** 根因取值：表述不当（首轮检索证据链不足或全空，换问法补查后命中，用户问法需更具体） */
    public static final String ROOT_CAUSE_EXPRESSION_ISSUE = "EXPRESSION_ISSUE";
    /** 根因取值：知识缺失（首轮检索为空，或多跳补查超限仍信息不足，知识库确实没有相关资料） */
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

    // ---- 旧版平铺字段 key：仅用于升级前 checkpoint 的迁移与兼容读取，新数据一律写入嵌套子对象 ----
    private static final String LEGACY_VALIDATED = "validated";
    private static final String LEGACY_VALIDATION_ISSUE = "validationIssue";
    private static final String LEGACY_VALIDATION_REASON = "validationReason";
    private static final String LEGACY_HOP_COUNT = "hopCount";
    private static final String LEGACY_PENDING_QUERY = "pendingQuery";
    private static final String LEGACY_HOP_SUFFICIENT = "hopSufficient";

    /**
     * 质检状态子对象（VALIDATION 嵌套值）：质检通过标记 + 失败类型 + 失败原因。
     * <p>
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
     * <p>
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

    public WorkflowState(Map<String, Object> initData) {
        super(migrateLegacyFields(initData));
    }

    /**
     * 旧版平铺字段迁移：升级前的 checkpoint 反序列化后 Map 中含平铺字段（validated/hopCount 等），
     * 重建状态时提升为嵌套子对象并移除旧 key，保证落库与断点续跑都是新结构。
     * <p>
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
     * <p>
     * hop 子对象整体归零（含 sufficient 复位为 false：新轮次无多跳判定，旧版平铺实现未归零 sufficient，
     * 嵌套化后一并复位语义更干净 —— 该标记只在 HopCheckNode 之后被路由读取，entry 复位不影响其消费）。
     * 新增跨轮计数字段时必须同步补充到此处 —— EntryNode 只需引用本方法，无需逐字段修改。
     *
     * @return 归零增量（不可变 Map，调用方合并进自身更新）
     */
    public static Map<String, Object> perTurnReset() {
        return Map.of(
                RETRY_COUNT, 0,
                HOP, Hop.of(0, "", false),
                ROOT_CAUSE, "");
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
     * 本轮待检索查询列表（entry 种入默认单查询，decompose 覆盖为分解结果）。
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

    /** 上下文不完整标记（多跳超限时标记 true，生成端据此声明信息可能不全）；缺省 false */
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
}
