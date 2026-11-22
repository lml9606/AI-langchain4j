package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.runtime.ToolErrorCode;
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
 * 任务执行节点：只负责按计划顺序调用工具并收集轨迹。
 *
 * <p>权限、参数 Schema、写契约由 {@link ToolExecutor} 在真正执行前最终校验；
 * 计划契约在规划和确认阶段已经校验过，这里不再重复展开整个工作流。
 * <p>
 * 观察语义最小化：某一步失败不抛异常，继续执行后续步骤；工具结果为空也保留轨迹，
 * 由回答节点统一说明“未查询到/查询失败”，避免半途中断导致用户拿不到任何反馈。
 */
@Slf4j
@Component
public class TaskExecuteNode implements NodeAction<WorkflowState> {

    /**
     * 观察判定：这些错误继续执行后续步骤也没有意义，直接停止，避免浪费调用配额。
     * 使用 ToolErrorCode 的枚举名而不是散落字符串，保证与执行器错误码同步。
     */
    private static final Set<String> FATAL_ERRORS =
            Set.of(ToolErrorCode.FORBIDDEN_WRITE.name(), ToolErrorCode.FORBIDDEN_TOOL.name(),
                    ToolErrorCode.CONFIRMATION_REQUIRED.name(),
                    ToolErrorCode.MISSING_IDEMPOTENCY_KEY.name(),
                    ToolErrorCode.UNKNOWN_TOOL.name(), ToolErrorCode.RATE_LIMITED.name(),
                    ToolErrorCode.OVERLOADED.name(), ToolErrorCode.INTERRUPTED.name(),
                    ToolErrorCode.TIMEOUT.name(), ToolErrorCode.INVALID_ARGS.name());

    private final ToolExecutor toolExecutor;
    private final AgentProperties properties;

    /** 注入统一工具执行器与执行上限配置。 */
    public TaskExecuteNode(ToolExecutor toolExecutor, AgentProperties properties) {
        this.toolExecutor = toolExecutor;
        this.properties = properties;
    }

    /** 按计划顺序调用工具，收集调用轨迹与证据；致命错误时提前停止。 */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        ToolContext context = new ToolContext(state.userId(), state.conversationId(),
                state.taskApproved(), state.taskIdempotencyKey());
        List<Map<String, Object>> trace = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        log.info("[task-execute] start, planSteps={}, approved={}, conversation={}",
                state.taskPlan().size(), state.taskApproved(), state.conversationId());
        if (state.taskPlan().isEmpty()) {
            log.warn("[task-execute] empty plan, conversation={}", state.conversationId());
            return fail("执行计划为空，拒绝执行", trace, evidence);
        }

        int maxSteps = Math.max(1, properties.getTool().getMaxStepsPerTask());
        // 步骤1：按计划顺序执行；每一步的结构化结果都写入 trace，成功步骤同时抽取证据
        for (Map<String, Object> step : state.taskPlan()) {
            // 防御性二次截断：即使计划被外部篡改，也不能突破工具执行次数上限
            if (trace.size() >= maxSteps
                    || trace.size() >= properties.getLimits().getTaskTraceMaxEntries()) {
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
            traceItem.put("summary", Texts.truncate(result.summary(),
                    properties.getLimits().getTaskSummaryMaxLength()));
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
                    if (evidence.size() >= properties.getLimits().getTaskEvidenceMaxEntries()) {
                        break;
                    }
                    Map<String, Object> evidenceItem = new LinkedHashMap<>();
                    evidenceItem.put("type", item.type());
                    evidenceItem.put("sourceId", item.sourceId());
                    evidenceItem.put("title", item.title());
                    evidenceItem.put("snippet", Texts.truncate(item.snippet(),
                            properties.getLimits().getTaskSummaryMaxLength()));
                    evidenceItem.put("score", item.score());
                    evidence.add(evidenceItem);
                }
            }
        }
        // 步骤2：返回轨迹与证据；不设置 error，回答节点会根据失败轨迹给出可解释答复
        long successCalls = trace.stream().filter(item -> Boolean.TRUE.equals(item.get("success"))).count();
        log.info("[task-execute] done, calls={}, success={}, evidence={}, approved={}",
                trace.size(), successCalls, evidence.size(), state.taskApproved());
        return Map.of(
                WorkflowState.TASK_TRACE, trace,
                WorkflowState.TASK_EVIDENCE, evidence,
                WorkflowState.ERROR, "");
    }

    /** 契约或权限失败：保留已产生轨迹，同时显式报错，避免回答节点把半份结果当成成功。 */
    private Map<String, Object> fail(String message, List<Map<String, Object>> trace,
                                     List<Map<String, Object>> evidence) {
        return Map.of(
                WorkflowState.TASK_TRACE, trace,
                WorkflowState.TASK_EVIDENCE, evidence,
                WorkflowState.ERROR, message);
    }

}
