package com.example.langchain4j.llm;

import dev.langchain4j.model.output.FinishReason;

/**
 * LLM 调用结果：文本 + Token 用量（用于审计与成本统计）+ 结束原因（截断感知）。
 */
public record LlmResult(String text, Integer tokenUsage, FinishReason finishReason) {

    public static LlmResult of(String text, Integer tokenUsage, FinishReason finishReason) {
        return new LlmResult(text == null ? "" : text, tokenUsage, finishReason);
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
