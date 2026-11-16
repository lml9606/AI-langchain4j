package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.runtime.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 任务执行节点：按计划逐步调用工具，收集调用轨迹与证据。
 * <p>
 * 观察语义最小化：某一步失败不抛异常，继续执行后续步骤；工具结果为空也保留轨迹，
 * 由回答节点统一说明“未查询到/查询失败”，避免半途中断导致用户拿不到任何反馈。
 */
@Slf4j
@Component
public class TaskExecuteNode implements NodeAction<WorkflowState> {

    private static final int MAX_TRACE = 8;
    private static final int MAX_EVIDENCE = 8;
    private static final int MAX_SUMMARY = 500;
    /** 观察判定：这些错误继续执行后续步骤也没有意义，直接停止，避免浪费调用配额 */
    private static final Set<String> FATAL_ERRORS =
            Set.of("FORBIDDEN_WRITE", "CONFIRMATION_REQUIRED", "UNKNOWN_TOOL", "RATE_LIMITED", "TIMEOUT");

    private final ToolExecutor toolExecutor;

    public TaskExecuteNode(ToolExecutor toolExecutor) {
        this.toolExecutor = toolExecutor;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        ToolContext context = new ToolContext(state.userId(), state.conversationId(),
                state.taskApproved(), state.taskIdempotencyKey());
        List<Map<String, Object>> trace = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();

        // 步骤1：按计划顺序执行；每一步的结构化结果都写入 trace，成功步骤同时抽取证据
        for (Map<String, Object> step : state.taskPlan()) {
            if (trace.size() >= MAX_TRACE) {
                break;
            }
            String tool = String.valueOf(step.get("tool"));
            Object rawArgs = step.get("args");
            JsonNode args = JsonUtil.mapper().valueToTree(rawArgs == null ? Map.of() : rawArgs);
            ToolResult result = toolExecutor.invoke(context, tool, args);

            Map<String, Object> traceItem = new LinkedHashMap<>();
            traceItem.put("tool", tool);
            traceItem.put("args", rawArgs == null ? Map.of() : rawArgs);
            traceItem.put("purpose", step.get("purpose"));
            traceItem.put("success", result.success());
            traceItem.put("summary", truncate(result.summary(), MAX_SUMMARY));
            traceItem.put("errorCode", result.errorCode());
            traceItem.put("costMs", result.costMs());
            trace.add(traceItem);

            // 步骤1.5：最小观察逻辑 —— 权限/限流/超时类失败不再继续后续步骤
            if (!result.success() && FATAL_ERRORS.contains(result.errorCode())) {
                log.warn("[task-execute] stop plan at tool={}, fatal error={}", tool, result.errorCode());
                break;
            }

            if (result.success()) {
                for (ToolEvidence item : result.evidence()) {
                    if (evidence.size() >= MAX_EVIDENCE) {
                        break;
                    }
                    Map<String, Object> evidenceItem = new LinkedHashMap<>();
                    evidenceItem.put("type", item.type());
                    evidenceItem.put("sourceId", item.sourceId());
                    evidenceItem.put("title", item.title());
                    evidenceItem.put("snippet", truncate(item.snippet(), MAX_SUMMARY));
                    evidenceItem.put("score", item.score());
                    evidence.add(evidenceItem);
                }
            }
        }
        // 步骤2：返回轨迹与证据；不设置 error，回答节点会根据失败轨迹给出可解释答复
        log.info("[task-execute] calls={}, evidence={}", trace.size(), evidence.size());
        return Map.of(
                WorkflowState.TASK_TRACE, trace,
                WorkflowState.TASK_EVIDENCE, evidence,
                WorkflowState.ERROR, "");
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
