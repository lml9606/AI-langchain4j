package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import com.example.langchain4j.tool.runtime.ConfirmationPolicy;
import com.example.langchain4j.tool.workflow.TaskPlanValidator;
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
    private final ConfirmationPolicy confirmationPolicy;
    private final TaskPlanValidator planValidator;

    /** 注入确认令牌服务、确认资格策略和计划校验器。 */
    public TaskConfirmNode(ConfirmationService confirmationService,
                           ConfirmationPolicy confirmationPolicy,
                           TaskPlanValidator planValidator) {
        this.confirmationService = confirmationService;
        this.confirmationPolicy = confirmationPolicy;
        this.planValidator = planValidator;
    }

    /** 为写工具创建一次性确认令牌，并返回待确认卡片，不执行实际写操作。 */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        Map<String, Object> pending = state.taskPendingAction();
        String toolName = Texts.trimmedTextOr(pending.get("tool"), "");
        Object rawArgs = pending.get("args");
        // 确认资格只由策略回答：工具是否存在、是否可写、角色是否有权限。
        ConfirmationPolicy.Decision confirmable =
                confirmationPolicy.check(toolName, UserContext.role());
        if (!confirmable.allowed()) {
            return error(confirmable.message());
        }
        ToolDefinition definition = confirmable.definition();

        List<Map<String, Object>> plan = state.taskPlan();
        if (plan.isEmpty()) {
            return error("确认请求缺少执行计划");
        }
        TaskPlanValidator.PlanValidationResult validation =
                planValidator.validateConfirm(plan, pending);
        if (!validation.valid()) {
            return error(validation.message());
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
