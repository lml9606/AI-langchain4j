package com.example.langchain4j.graph.state;

import org.bsc.langgraph4j.state.AgentState;

import java.util.Map;

/**
 * 工作流状态：LangGraph4j 图内共享的状态容器。
 * 说明：所有节点只能通过返回 Map 更新状态（不可变更新），禁止直接修改 data。
 * 继承框架的 {@link AgentState}（与 {@code com.example.langchain4j.graph.state.WorkflowState}
 * 同简单名，注意 import 范围）。
 */
public class WorkflowState extends AgentState {

    public static final String QUERY = "query";
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

    public String query() {
        return value(QUERY, "");
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
        Object v = value(RETRY_COUNT).orElse(0);
        return v instanceof Number number ? number.intValue() : 0;
    }

    public boolean validated() {
        return Boolean.parseBoolean(String.valueOf(value(VALIDATED).orElse(false)));
    }

    public String error() {
        return value(ERROR, "");
    }

    public boolean fallback() {
        return Boolean.parseBoolean(String.valueOf(value(FALLBACK).orElse(false)));
    }

    public Integer tokenUsage() {
        Object v = value(TOKEN_USAGE).orElse(null);
        return v instanceof Number number ? number.intValue() : null;
    }

    public boolean done() {
        return Boolean.parseBoolean(String.valueOf(value(DONE).orElse(false)));
    }
}
