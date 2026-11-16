package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.service.AuditService;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 工具执行器：参数校验、只读校验、超时、审计、指标统一收口。
 * 任何异常都转成结构化 ToolResult，不向图抛裸异常。
 */
@Slf4j
@Service
public class ToolExecutor {

    private final ToolRegistry registry;
    private final AuditService auditService;
    private final AgentMetrics metrics;
    private final ThreadPoolTaskExecutor executor;
    private final AgentProperties properties;
    /** 分布式固定窗口限流：多实例共享 Redis 计数 */
    private final RateLimiter rateLimiter;

    public ToolExecutor(ToolRegistry registry,
                        AuditService auditService,
                        AgentMetrics metrics,
                        @Qualifier("toolExecutorPool") ThreadPoolTaskExecutor executor,
                        AgentProperties properties,
                        RateLimiter rateLimiter) {
        this.registry = registry;
        this.auditService = auditService;
        this.metrics = metrics;
        this.executor = executor;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
    }

    public ToolResult invoke(ToolContext context, String toolName, JsonNode args) {
        long start = System.currentTimeMillis();
        ToolResult result;
        AgentTool tool = registry.find(toolName).orElse(null);
        if (tool == null) {
            result = ToolResult.failure("UNKNOWN_TOOL", "未知工具: " + toolName,
                    System.currentTimeMillis() - start);
            record(context, toolName, args, result, start);
            return result;
        }
        ToolDefinition definition = tool.definition();
        // 步骤1.5：单用户每分钟调用上限（Redis 固定窗口，多实例共享；只读工具也不能无限刷）
        String rateKey = "tool:" + (context.userId().isBlank() ? "anonymous" : context.userId());
        if (!rateLimiter.tryAcquire(rateKey, properties.getTool().getMaxCallsPerMinute())) {
            result = ToolResult.failure("RATE_LIMITED", "工具调用过于频繁，请稍后重试",
                    System.currentTimeMillis() - start);
            record(context, toolName, args, result, start);
            return result;
        }
        // 步骤1.6：写工具双门禁 —— 环境白名单 + 用户确认，任一不满足都拒绝执行
        if (definition.sideEffect() != ToolSideEffect.READ_ONLY) {
            if (properties.getTool().isReadOnly() || !writeToolAllowed(toolName)) {
                result = ToolResult.failure("FORBIDDEN_WRITE", "非只读工具在当前环境被禁用: " + toolName,
                        System.currentTimeMillis() - start);
                record(context, toolName, args, result, start);
                return result;
            }
            if (!context.approved()) {
                result = ToolResult.failure("CONFIRMATION_REQUIRED", "写工具需要用户确认后执行: " + toolName,
                        System.currentTimeMillis() - start);
                record(context, toolName, args, result, start);
                return result;
            }
        }
        String missing = missingArg(definition, args);
        if (missing != null) {
            result = ToolResult.failure("INVALID_ARGS", "缺少必填参数: " + missing,
                    System.currentTimeMillis() - start);
            record(context, toolName, args, result, start);
            return result;
        }

        int timeoutMs = definition.timeoutMs() > 0 ? definition.timeoutMs() : properties.getTool().getTimeoutMs();
        Future<ToolResult> future = executor.getThreadPoolExecutor()
                .submit(() -> tool.execute(context, args));
        try {
            result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            if (result == null) {
                result = ToolResult.failure("EMPTY_RESULT", "工具返回空结果",
                        System.currentTimeMillis() - start);
            }
        } catch (TimeoutException e) {
            future.cancel(true);
            result = ToolResult.failure("TIMEOUT", "工具执行超时: " + timeoutMs + "ms",
                    System.currentTimeMillis() - start);
        } catch (Exception e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            result = ToolResult.failure("FAILED", rootMessage(cause),
                    System.currentTimeMillis() - start);
        }
        record(context, toolName, args, result, start);
        return result;
    }

    private String missingArg(ToolDefinition definition, JsonNode args) {
        for (String required : definition.requiredArgs()) {
            JsonNode value = args == null ? null : args.get(required);
            if (value == null || value.isNull()
                    || (value.isTextual() && value.asText().isBlank())) {
                return required;
            }
        }
        return null;
    }

    /** 写工具白名单匹配：配置为空时拒绝所有写工具 */
    private boolean writeToolAllowed(String toolName) {
        return properties.getTool().getAllowedWriteTools() != null
                && properties.getTool().getAllowedWriteTools().contains(toolName);
    }

    private void record(ToolContext context, String toolName, JsonNode args, ToolResult result, long start) {
        long cost = Math.max(result.costMs(), System.currentTimeMillis() - start);
        try {
            metrics.recordToolCall(toolName, result.success(), cost);
        } catch (Exception ignored) {
            // 指标失败不影响业务
        }
        String conversationId = context.conversationId().isBlank() ? "tool-debug" : context.conversationId();
        auditService.audit(conversationId, "tool:" + toolName,
                result.success() ? GraphExecution.STATUS_SUCCESS : GraphExecution.STATUS_FAILED,
                JsonUtil.toJson(args), JsonUtil.toJson(result.summary()),
                result.success() ? null : result.errorCode() + ":" + result.errorMessage(), cost);
        log.info("[tool] name={}, ok={}, cost={}ms, error={}", toolName, result.success(), cost,
                result.success() ? "" : result.errorCode());
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }
}
