package com.example.langchain4j.tool.api.model;

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

    /** 归一化结果字段：证据列表做不可变拷贝，缺失文本统一转空串。 */
    public ToolResult {
        summary = summary == null ? "" : summary;
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    /** 构造成功结果；data 供程序使用，summary 供模型阅读。 */
    public static ToolResult success(Object data, String summary, List<ToolEvidence> evidence, long costMs) {
        return new ToolResult(true, data, summary, "", "", evidence, costMs);
    }

    /** 构造失败结果；保留错误码和错误信息，证据固定为空。 */
    public static ToolResult failure(String errorCode, String errorMessage, long costMs) {
        return new ToolResult(false, null, "", errorCode, errorMessage, List.of(), costMs);
    }
}
