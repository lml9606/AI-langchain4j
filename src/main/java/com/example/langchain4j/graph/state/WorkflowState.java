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
    public static final String QUERY = "query";
    public static final String REWRITTEN_QUERY = "rewrittenQuery";
    public static final String HISTORY = "history";
    public static final String SUMMARY = "summary";
    public static final String MODEL = "model";
    public static final String INTENT = "intent";
    public static final String INTENT_REASON = "intentReason";
    public static final String CONTEXT = "context";
    public static final String RESULT = "result";
    public static final String RETRY_COUNT = "retryCount";
    public static final String VALIDATED = "validated";
    /** 校验失败类型（format=规则层失败 / grounding=LLM 质检发现与资料矛盾），供路由决定回生成还是回检索 */
    public static final String VALIDATION_ISSUE = "validationIssue";
    /** 校验失败原因（LLM 质检给出的具体矛盾描述），供重查时改写 query 与重试指令引用 */
    public static final String VALIDATION_REASON = "validationReason";
    public static final String ERROR = "error";
    public static final String FALLBACK = "fallback";
    public static final String TOKEN_USAGE = "tokenUsage";
    public static final String DONE = "done";

    public WorkflowState(Map<String, Object> initData) {
        super(initData);
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
