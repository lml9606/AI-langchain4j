package com.example.langchain4j.audit;

import com.example.langchain4j.common.util.TraceIdUtil;

import java.util.Map;
import java.util.Collections;
import java.util.LinkedHashMap;

/**
 * 一次审计记录：执行事实的统一载体，与存储实现解耦。
 *
 * <p>executionId 优先取当前请求 traceId，保证同一次请求的节点、工具、改写、
 * 整体执行记录可以串成一条链路；没有 MDC 时退化为随机 ID。
 */
public record AuditEntry(String executionId,
                         String conversationId,
                         String nodeName,
                         String status,
                         Map<String, Object> input,
                         Map<String, Object> output,
                         String errorMsg,
                         Long costMs,
                         String rootCause) {

    public AuditEntry {
        executionId = executionId == null || executionId.isBlank()
                ? TraceIdUtil.get() : executionId;
        conversationId = conversationId == null ? "" : conversationId;
        nodeName = nodeName == null ? "" : nodeName;
        status = status == null ? "" : status;
        input = input == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(input));
        output = output == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(output));
        rootCause = rootCause == null || rootCause.isBlank() ? null : rootCause;
    }

    /** 便捷构造：executionId 自动取当前 traceId。 */
    public static AuditEntry of(String conversationId, String nodeName, String status,
                                Map<String, Object> input, Map<String, Object> output,
                                String errorMsg, Long costMs, String rootCause) {
        return new AuditEntry(TraceIdUtil.get(), conversationId, nodeName, status, input, output,
                errorMsg, costMs, rootCause);
    }

    /**
     * 文本审计便捷构造：适用于只有一个查询和一个结果的步骤，
     * 统一包装为 {@code input.query / output.result}，避免调用方重复拼 Map。
     */
    public static AuditEntry text(String conversationId, String nodeName, String status,
                                  String input, String output, String errorMsg, Long costMs,
                                  String rootCause) {
        return of(conversationId, nodeName, status,
                Map.of("query", input == null ? "" : input),
                Map.of("result", output == null ? "" : output),
                errorMsg, costMs, rootCause);
    }
}
