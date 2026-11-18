package com.example.langchain4j.graph.qa;

/**
 * 单次生成回答预算：提示词目标字数 + 供应商输出 token 硬上限 + 结构化长度要求。
 *
 * @param maxChars        目标字数；0 表示不追加字数提示（如代码生成）
 * @param maxOutputTokens 模型输出 token 硬上限
 * @param styleNote       面向当前问题形态的长度/结构要求
 */
public record AnswerBudget(int maxChars, int maxOutputTokens, String styleNote) {

    public AnswerBudget {
        maxChars = Math.max(0, maxChars);
        maxOutputTokens = Math.max(64, maxOutputTokens);
        styleNote = styleNote == null ? "" : styleNote;
    }

    public boolean hasCharLimit() {
        return maxChars > 0 && !styleNote.isBlank();
    }
}
