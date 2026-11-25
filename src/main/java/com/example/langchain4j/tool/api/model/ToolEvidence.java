package com.example.langchain4j.tool.api.model;

/**
 * 工具证据：可回填到最终答案引用的结构化依据。
 */
public record ToolEvidence(String type, String sourceId, String title, String snippet, double score) {

    /** 归一化证据字段：缺失文本统一转空串，保证引用渲染不出现 null。 */
    public ToolEvidence {
        type = type == null ? "TOOL" : type;
        sourceId = sourceId == null ? "" : sourceId;
        title = title == null ? "" : title;
        snippet = snippet == null ? "" : snippet;
    }
}
