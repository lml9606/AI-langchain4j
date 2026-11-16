package com.example.langchain4j.tool.api;

import java.util.List;

/**
 * 工具执行结果：结构化数据给程序，summary 给模型，evidence 给引用。
 */
public record ToolResult(boolean success,
                         Object data,
                         String summary,
                         String errorCode,
                         String errorMessage,
                         List<ToolEvidence> evidence,
                         long costMs) {

    public ToolResult {
        summary = summary == null ? "" : summary;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    public static ToolResult success(Object data, String summary, List<ToolEvidence> evidence, long costMs) {
        return new ToolResult(true, data, summary, "", "", evidence, costMs);
    }

    public static ToolResult failure(String errorCode, String errorMessage, long costMs) {
        return new ToolResult(false, null, "", errorCode, errorMessage, List.of(), costMs);
    }
}
