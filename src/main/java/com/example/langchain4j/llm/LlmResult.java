package com.example.langchain4j.llm;

/**
 * LLM 调用结果：文本 + Token 用量（用于审计与成本统计）。
 */
public record LlmResult(String text, Integer tokenUsage) {

    public static LlmResult of(String text, Integer tokenUsage) {
        return new LlmResult(text == null ? "" : text, tokenUsage);
    }
}
