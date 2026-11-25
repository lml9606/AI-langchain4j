package com.example.langchain4j.llm.model;

import dev.langchain4j.model.output.FinishReason;

/**
 * LLM 调用结果：文本 + Token 用量（用于审计与成本统计）+ 结束原因（截断感知）。
 * tokenUsage 为总量；input/output 分开记录，便于定位耗时来自上下文还是生成。
 */
public record LlmResult(String text, Integer tokenUsage, FinishReason finishReason,
                        Integer inputTokens, Integer outputTokens) {

    /** 兼容只关心总 token 的调用方；输入/输出 token 留空。 */
    public LlmResult(String text, Integer tokenUsage, FinishReason finishReason) {
        this(text, tokenUsage, finishReason, null, null);
    }

    /** 兼容旧调用方式，构造时不拆输入/输出 token。 */
    public static LlmResult of(String text, Integer tokenUsage, FinishReason finishReason) {
        return of(text, tokenUsage, finishReason, null, null);
    }

    /** 构造结果并把 null 文本归一为空串，避免调用方重复判空。 */
    public static LlmResult of(String text, Integer tokenUsage, FinishReason finishReason,
                               Integer inputTokens, Integer outputTokens) {
        return new LlmResult(text == null ? "" : text, tokenUsage, finishReason,
                inputTokens, outputTokens);
    }

    /**
     * 输出是否被 maxOutputTokens 截断。
     * 截断时结构化 JSON 大概率不完整，调用方应直接走 fail-open 降级路径，不再解析残缺输出。
     * 部分供应商不回传结束原因（finishReason 为 null），视为未截断。
     */
    public boolean truncated() {
        return FinishReason.LENGTH.equals(finishReason);
    }
}
