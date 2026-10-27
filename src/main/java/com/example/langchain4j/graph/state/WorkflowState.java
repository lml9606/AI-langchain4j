package com.example.langchain4j.graph.state;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

/**
 * 工作流状态：LangGraph4j 图内共享的状态容器。
 * <p>
 * 更新机制：节点不直接改状态，而是返回 {@code Map<String,Object>} 增量更新 ——
 * 框架把返回值合并进状态后生成新状态对象传给下一个节点（不可变更新），
 * 每个节点看到的是“前序节点完成后”的快照，天然支持 checkpoint 回放与并发安全。
 * 继承框架的 {@link AgentState}（与 {@code com.example.langchain4j.graph.state.WorkflowState}
 * 同简单名，注意 import 范围）。
 */
public class WorkflowState extends AgentState {

    // ---- 状态字段名常量：节点写入 Map 与访问器取值共用同一 key，避免字符串漂移 ----
    public static final String QUERY = "query";
    public static final String MODEL = "model";
    public static final String INTENT = "intent";
    public static final String INTENT_REASON = "intentReason";
    public static final String CONTEXT = "context";
    public static final String RESULT = "result";
    public static final String RETRY_COUNT = "retryCount";
    public static final String VALIDATED = "validated";
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
    // （如 checkpoint 反序列化后类型漂移），因此逐个 instanceof 判断。

    public String query() {
        return value(QUERY, "");
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
        Object v = value(RETRY_COUNT).orElse(0);
        // 步骤2：防御类型漂移 —— 非数字（如被写成字符串）一律回退 0
        return v instanceof Number number ? number.intValue() : 0;
    }

    public boolean validated() {
        // 步骤1：取出原始值（缺省 false）
        // 步骤2：统一转为字符串后解析布尔，兼容 "true"/布尔/数字等写法
        return Boolean.parseBoolean(String.valueOf(value(VALIDATED).orElse(false)));
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
