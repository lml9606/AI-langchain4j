package com.example.langchain4j.knowledge;

/**
 * 一次检索的结构化结果：上下文 + 命中数量 + 融合最高分。
 * <p>
 * 上下文供生成使用；命中数量和分数供证据评估使用。RRF 分数只用于同一轮检索内部的
 * 相对强弱比较，不作为概率置信度直接透出。
 */
public record KnowledgeRetrievalResult(String context, int hitCount, double topScore) {

    public KnowledgeRetrievalResult {
        context = context == null ? "" : context;
        hitCount = Math.max(0, hitCount);
        topScore = Math.max(0.0, topScore);
    }

    public static KnowledgeRetrievalResult empty() {
        return new KnowledgeRetrievalResult("", 0, 0.0);
    }

    public boolean hasContext() {
        return !context.isBlank();
    }
}
