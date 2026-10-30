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
    /** 质检通过标记（ValidateNode 写入；true 转收尾，false 按失败类型回生成或回检索） */
    public static final String VALIDATED = "validated";
    /** 校验失败类型（format=规则层失败 / grounding=LLM 质检发现与资料矛盾），供路由决定回生成还是回检索 */
    public static final String VALIDATION_ISSUE = "validationIssue";
    /** 校验失败原因（LLM 质检给出的具体矛盾描述），供重查时改写 query 与重试指令引用 */
    public static final String VALIDATION_REASON = "validationReason";
    /** 问题分解后的子查询列表（DecomposeNode 写入；code_generate 意图跳过分解，列表为空时检索用 effectiveQuery） */
    public static final String SUB_QUERIES = "subQueries";
    /** 多跳检索已执行轮数（EntryNode 归零、HopCheckNode 判定信息不足时 +1，不含首轮检索） */
    public static final String HOP_COUNT = "hopCount";
    /** 下一跳检索查询（HopCheckNode 判定信息不足时写入，RetrievalNode 多跳轮据此检索；上下文足够时清空） */
    public static final String PENDING_QUERY = "pendingQuery";
    /** 多跳判断结果（HopCheckNode 写入：true=上下文足够/超限，路由去生成；false=信息不足，路由回检索） */
    public static final String HOP_SUFFICIENT = "hopSufficient";
    /** 上下文不完整标记（多跳超限仍信息不足时标记 true，生成端据此在 prompt 中声明信息可能不全） */
    public static final String CONTEXT_INCOMPLETE = "contextIncomplete";
    /** 检索根因标记（EntryNode 归零；写入点与取值见 ROOT_CAUSE_* 常量注释；透出到响应与审计表供用户区分与数据闭环） */
    public static final String ROOT_CAUSE = "rootCause";
    /** 根因取值：问题太复杂（并列复合问题，分解后子查询分别命中召回，用户问法本身没问题） */
    public static final String ROOT_CAUSE_COMPLEX_QUERY = "COMPLEX_QUERY";
    /** 根因取值：表述不当（首轮检索证据链不足，多跳改写补查后命中，用户问法需更具体） */
    public static final String ROOT_CAUSE_EXPRESSION_ISSUE = "EXPRESSION_ISSUE";
    /** 根因取值：知识缺失（首轮检索为空，或多跳补查超限仍信息不足，知识库确实没有相关资料） */
    public static final String ROOT_CAUSE_KNOWLEDGE_MISSING = "KNOWLEDGE_MISSING";
    /** 错误标记（成功路径清空，Generate 失败写入原因；Router 发现非空即转兜底） */
    public static final String ERROR = "error";
    /** 兜底降级标记（FallbackNode 写入 true，透出到响应供调用方识别降级应答） */
    public static final String FALLBACK = "fallback";
    /** 本轮生成消耗的 token 总数（GenerateNode 写入，模型未返回时为空；用于审计与成本统计） */
    public static final String TOKEN_USAGE = "tokenUsage";
    /** 流程完成标记（FinalizeNode 写入 true，透出到响应） */
    public static final String DONE = "done";

    public WorkflowState(Map<String, Object> initData) {
        super(initData);
    }

    /**
     * 每轮重置的跨轮计数字段：EntryNode 在每轮入口调用（与自身更新合并返回），
     * 归零 retryCount/hopCount/pendingQuery/rootCause，防断点恢复重入时上一轮残留影响本轮。
     * <p>
     * 新增跨轮计数字段时必须同步补充到此处 —— EntryNode 只需引用本方法，无需逐字段修改。
     *
     * @return 归零增量（不可变 Map，调用方合并进自身更新）
     */
    public static Map<String, Object> perTurnReset() {
        return Map.of(
                RETRY_COUNT, 0,
                HOP_COUNT, 0,
                PENDING_QUERY, "",
                ROOT_CAUSE, "");
    }

    // ---- 类型安全的访问器（带默认值，防御 null） ----
    //
    // 为什么不用字段直接取值：状态底层是 Map<String,Object>，任何节点都可能写错类型
    // 或漏写 key；访问器统一做类型转换 + 默认值兜底，节点代码永远拿到安全值。
    // retryCount/validated/tokenUsage 等还需防御“数字/布尔被写成字符串”的场景
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

    public boolean validated() {
        // 步骤1：取出原始值（缺省 false）
        // 步骤2：统一转为字符串后解析布尔，兼容 "true"/布尔/数字等写法
        return Boolean.parseBoolean(String.valueOf(value(VALIDATED).orElse(false)));
    }

    /** 校验失败类型：format=规则层失败（回生成重试）；grounding=与资料矛盾（回检索重查）；未失败时为空 */
    public String validationIssue() {
        return value(VALIDATION_ISSUE, "");
    }

    /** 校验失败原因：LLM 质检的矛盾描述，供重查改写 query 与重试指令引用；未失败时为空 */
    public String validationReason() {
        return value(VALIDATION_REASON, "");
    }

    /**
     * 问题分解后的子查询列表（DecomposeNode 写入）；未分解/代码生成意图时为空列表。
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

    /** 多跳检索已执行轮数（不含首轮）；防御类型漂移，解析失败回退 0 */
    public int hopCount() {
        // 步骤1：取出原始值（缺省 0）
        Object v = value(HOP_COUNT, 0);
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

    /** 下一跳检索查询（HopCheckNode 写入）；无下一跳时为空串 */
    public String pendingQuery() {
        return value(PENDING_QUERY, "");
    }

    /** 多跳判断结果：true=上下文足够/超限（路由去生成）；false=信息不足（路由回检索）；缺省 false */
    public boolean hopSufficient() {
        // 步骤1：取出原始值（缺省 false）
        // 步骤2：统一转为字符串后解析布尔，防御类型漂移
        return Boolean.parseBoolean(String.valueOf(value(HOP_SUFFICIENT).orElse(false)));
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
}
