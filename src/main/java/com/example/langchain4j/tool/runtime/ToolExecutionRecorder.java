package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.service.AuditService;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具执行记录器：指标、审计、日志统一收口。
 *
 * <p>记录失败不能影响工具结果：指标异常被吞掉，审计内部也会降级为告警。
 * 单独抽出后，确认链路或其他执行入口可以复用同一套记录口径。
 */
@Slf4j
public final class ToolExecutionRecorder {

    private final AuditService auditService;
    private final AgentMetrics metrics;

    public ToolExecutionRecorder(AuditService auditService, AgentMetrics metrics) {
        this.auditService = auditService;
        this.metrics = metrics;
    }

    /**
     * 记录一次工具调用并原样返回结果，方便调用方链式返回。
     *
     * @param start 调用开始时间；工具自报耗时与墙钟耗时取较大值，避免少报
     */
    public ToolResult record(ToolContext context, String toolName, JsonNode args,
                             ToolResult result, long start) {
        long cost = Math.max(result.costMs(), System.currentTimeMillis() - start);
        try {
            metrics.recordToolCall(toolName, result.success(), cost);
        } catch (Exception ignored) {
            // 指标失败不影响业务
        }
        String conversationId = context.conversationId().isBlank()
                ? "tool-debug" : context.conversationId();
        auditService.audit(conversationId, "tool:" + toolName,
                result.success() ? GraphExecution.STATUS_SUCCESS : GraphExecution.STATUS_FAILED,
                JsonUtil.toJson(args), JsonUtil.toJson(result.summary()),
                result.success() ? null : result.errorCode() + ":" + result.errorMessage(), cost);
        log.info("[tool] name={}, ok={}, cost={}ms, error={}", toolName, result.success(), cost,
                result.success() ? "" : result.errorCode());
        return result;
    }
}
