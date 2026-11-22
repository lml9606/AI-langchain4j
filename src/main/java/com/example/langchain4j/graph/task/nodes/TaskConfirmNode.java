package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import com.example.langchain4j.tool.runtime.ToolAccessPolicy;
import com.example.langchain4j.tool.runtime.ToolInputValidator;
import com.example.langchain4j.tool.runtime.ToolRegistry;
import com.example.langchain4j.tool.workflow.TaskWorkflow;
import com.example.langchain4j.tool.workflow.TaskWorkflowRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写操作确认节点：不执行工具，只生成一次性确认令牌和确认话术。
 * <p>
 * 真正的执行发生在 POST /api/agent/confirm 之后，由确认执行图携带 approved=true 完成；
 * 这样规划阶段即使被 prompt injection 诱导，也无法直接修改业务数据。
 */
@Slf4j
@Component
public class TaskConfirmNode implements NodeAction<WorkflowState> {

    private final ConfirmationService confirmationService;
    private final ToolRegistry toolRegistry;
    private final TaskWorkflowRegistry workflowRegistry;
    private final ToolAccessPolicy accessPolicy;
    private final AgentProperties properties;

    /** 注入确认令牌服务与工具注册表。 */
    public TaskConfirmNode(ConfirmationService confirmationService, ToolRegistry toolRegistry,
                           TaskWorkflowRegistry workflowRegistry, ToolAccessPolicy accessPolicy,
                           AgentProperties properties) {
        this.confirmationService = confirmationService;
        this.toolRegistry = toolRegistry;
        this.workflowRegistry = workflowRegistry;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
    }

    /** 为写工具创建一次性确认令牌，并返回待确认卡片，不执行实际写操作。 */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        Map<String, Object> pending = state.taskPendingAction();
        String toolName = Texts.trimmedTextOr(pending.get("tool"), "");
        Object rawArgs = pending.get("args");
        if (toolName.isBlank()) {
            return error("确认请求缺少待执行工具");
        }
        // 全局写开关先做一次快速失败，给出确认场景专属提示；策略层稍后还会再校验一次。
        if (!properties.getTool().isWriteEnabled()) {
            return error("写工具在当前环境已禁用，不能创建确认请求");
        }
        // 一次查找同时拿到实现和启动期固化的定义，避免 find + definition 两次查找。
        ToolRegistry.RegisteredTool registered = toolRegistry.findRegistered(toolName).orElse(null);
        if (registered == null) {
            return error("待执行工具不存在或已停用: " + toolName);
        }
        ToolDefinition definition = registered.definition();
        if (definition.sideEffect() == ToolSideEffect.READ_ONLY) {
            return error("只读工具不需要人工确认: " + toolName);
        }
        // 危险工具、角色、写开关统一由策略判定，确认节点不再维护第二套规则。
        ToolAccessPolicy.Decision access = accessPolicy.check(definition, UserContext.role());
        if (!access.allowed()) {
            return error(access.message());
        }

        List<Map<String, Object>> plan = state.taskPlan();
        if (plan.isEmpty()) {
            return error("确认请求缺少执行计划");
        }
        String workflowId = state.taskWorkflowId();
        if (workflowId.isBlank()) {
            workflowId = workflowRegistry.workflowIdOfPlan(plan).orElse("");
        }
        TaskWorkflow workflow = workflowId.isBlank() ? null
                : workflowRegistry.find(workflowId).orElse(null);
        if (workflow == null) {
            return error("确认请求缺少有效的任务工作流契约");
        }
        TaskWorkflowRegistry.Validation contract = workflowRegistry.validatePlan(workflow, plan);
        if (!contract.valid()) {
            return error(contract.message());
        }
        ToolAccessPolicy.Decision workflowAccess = accessPolicy.checkAll(workflow, UserContext.role());
        if (!workflowAccess.allowed()) {
            return error(workflowAccess.message());
        }
        Map<String, Object> writeStep = null;
        for (Map<String, Object> step : plan) {
            String stepTool = Texts.trimmedTextOr(step.get("tool"), "");
            if (stepTool.isBlank()) {
                return error("执行计划缺少工具名");
            }
            ToolRegistry.RegisteredTool stepRegistered = toolRegistry.findRegistered(stepTool).orElse(null);
            if (stepRegistered == null) {
                return error("执行计划包含不存在或已停用的工具: " + stepTool);
            }
            // 危险工具/角色/写开关已由上面的 checkAll 对整组契约校验过，这里只验证参数。
            ToolDefinition stepDefinition = stepRegistered.definition();
            JsonNode stepArgs = JsonUtil.mapper().valueToTree(
                    step.get("args") == null ? Map.of() : step.get("args"));
            ToolInputValidator.Result validation = ToolInputValidator.validate(stepDefinition, stepArgs);
            if (!validation.valid()) {
                return error("工具 " + stepTool + " 参数不合法：" + validation.message());
            }
            if (stepDefinition.sideEffect() != ToolSideEffect.READ_ONLY) {
                if (writeStep != null) {
                    return error("一次只能确认一个写操作，请拆分为多轮处理");
                }
                writeStep = step;
            }
        }
        if (writeStep == null) {
            return error("执行计划中没有待确认的写操作");
        }
        if (!toolName.equals(Texts.trimmedTextOr(writeStep.get("tool"), ""))) {
            return error("待确认工具与执行计划不一致");
        }
        JsonNode planArgs = JsonUtil.mapper().valueToTree(
                writeStep.get("args") == null ? Map.of() : writeStep.get("args"));
        JsonNode pendingArgs = JsonUtil.mapper().valueToTree(rawArgs == null ? Map.of() : rawArgs);
        if (!planArgs.equals(pendingArgs)) {
            return error("待确认参数与执行计划不一致");
        }

        String description = definition.description();
        String summary = "即将执行：" + description + "。参数："
                + JsonUtil.toJson(rawArgs == null ? Map.of() : rawArgs) + "。";
        String risk = definition.sideEffect().name();
        log.info("[task-confirm] creating token, tool={}, planSteps={}, conversation={}",
                toolName, state.taskPlan().size(), state.conversationId());

        try {
            ConfirmationService.PendingAction action = confirmationService.create(
                    state.userId(), state.conversationId(), state.model(), toolName,
                    state.taskPlan(), summary, risk);
            // 响应侧只透出展示字段；userId/conversationId 留在 Redis 令牌内部
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("confirmationId", action.confirmationId());
            view.put("tool", toolName);
            view.put("args", rawArgs == null ? Map.of() : rawArgs);
            view.put("summary", summary);
            view.put("risk", risk);
            view.put("expiresAt", action.expiresAt());

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.TASK_CONFIRMATION_ID, action.confirmationId());
            updates.put(WorkflowState.TASK_PENDING_ACTION, view);
            updates.put(WorkflowState.RESULT,
                    summary + "\n\n该操作会修改业务数据，请确认后执行。");
            updates.put(WorkflowState.TASK_CONFIDENCE, 1.0);
            updates.put(WorkflowState.TASK_CONFIDENCE_REASON, "等待用户确认写操作");
            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-confirm] token created, tool={}, idTail={}, conversation={}",
                    toolName, idTail(action.confirmationId()), state.conversationId());
            return updates;
        } catch (BizException e) {
            log.warn("[task-confirm] create token failed: {}", e.getMessage());
            return error("确认请求创建失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[task-confirm] unexpected error", e);
            return error("确认请求创建失败: " + e.getMessage());
        }
    }

    /** 失败时同时清空待确认卡片，避免响应透出没有 confirmationId 的半成品 */
    private Map<String, Object> error(String message) {
        return Map.of(
                WorkflowState.ERROR, message,
                WorkflowState.TASK_PENDING_ACTION, Map.of(),
                WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
    }

    /** 确认令牌脱敏：长 ID 仅保留末 8 位，短 ID 完全隐藏。 */
    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
