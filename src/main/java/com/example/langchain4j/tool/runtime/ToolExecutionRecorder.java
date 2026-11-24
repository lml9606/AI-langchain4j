package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolResult;
import lombok.extern.slf4j.Slf4j;

/**
 * 工具执行记录器：指标与日志统一收口。
 *
 * 记录器自身不把指标异常抛给业务，指标失败只降级为缺一条时序数据。
 * 单独抽出后，确认链路或其他执行入口可以复用同一套记录口径。
 */
@Slf4j
public final class ToolExecutionRecorder {

    private final AgentMetrics metrics;

    public ToolExecutionRecorder(AgentMetrics metrics) {
        this.metrics = metrics;
    }

    /**
     * 记录一次工具调用并原样返回结果，方便调用方链式返回。
     *
     * @param context 工具上下文；这里只用会话标识做日志关联，缺失时记为 tool-debug
     * @param start 调用开始时间；工具自报耗时与墙钟耗时取较大值，避免少报
     */
    public ToolResult record(ToolContext context, String toolName, ToolResult result, long start) {
        long cost = Math.max(result.costMs(), System.currentTimeMillis() - start);
        try {
            metrics.recordToolCall(toolName, result.success(), cost);
        } catch (Exception ignored) {
            // 指标失败不影响业务
        }
        String conversationId = context.conversationId().isBlank()
                ? "tool-debug" : context.conversationId();
        log.info("[tool] conversation={}, name={}, ok={}, cost={}ms, error={}", conversationId,
                toolName, result.success(), cost, result.success() ? "" : result.errorCode());
        return result;
    }
}
