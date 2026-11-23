package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.repository.entity.TaskStep;
import com.example.langchain4j.service.TaskRunService;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import com.example.langchain4j.tool.runtime.ConfirmationPolicy;
import com.example.langchain4j.tool.runtime.TaskArgsResolver;
import com.example.langchain4j.tool.workflow.TaskPlanValidator;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
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
    private final TaskRunService taskRunService;
    private final TaskArgsResolver argsResolver;

    /** 生产构造：批量确认需要任务/步骤快照和参数引用解析。 */
    @Autowired
    public TaskConfirmNode(ConfirmationService confirmationService,
                           ConfirmationPolicy confirmationPolicy,
                           TaskPlanValidator planValidator,
                           TaskRunService taskRunService,
                           TaskArgsResolver argsResolver) {
        this.confirmationService = confirmationService;
        this.confirmationPolicy = confirmationPolicy;
        this.planValidator = planValidator;
        this.taskRunService = taskRunService;
        this.argsResolver = argsResolver;
    }

    /** 兼容旧测试/旧 checkpoint：没有任务运行时退化到单写确认。 */
    public TaskConfirmNode(ConfirmationService confirmationService,
                           ConfirmationPolicy confirmationPolicy,
                           TaskPlanValidator planValidator) {
        this(confirmationService, confirmationPolicy, planValidator, null, null);
    }

    /** 为写工具创建一次性确认令牌，并返回待确认卡片，不执行实际写操作。 */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        if (state.taskRunId().isBlank() || taskRunService == null || argsResolver == null) {
            return applyLegacy(state);
        }
        return applyBatch(state);
    }

    /** 多步骤确认：把全部写步骤放进一张确认卡片，参数使用解析后的真实值。 */
    private Map<String, Object> applyBatch(WorkflowState state) {
        String runId = state.taskRunId();
        TaskRun run = taskRunService.find(state.userId(), runId).orElse(null);
        if (run == null) {
            return error("确认请求对应的任务不存在或已过期");
        }
        List<TaskStep> steps = taskRunService.steps(runId);
        Map<Integer, TaskStep> byNo = new LinkedHashMap<>();
        for (TaskStep step : steps) {
            byNo.put(step.getStepNo(), step);
        }
        List<TaskStep> writeSteps = steps.stream().filter(TaskStep::write).toList();
        if (writeSteps.isEmpty()) {
            return error("执行计划中没有待确认的写操作");
        }

        List<ConfirmationService.PendingWriteStep> pendingSteps = new ArrayList<>();
        List<Map<String, Object>> resolvedPlan = new ArrayList<>();
        StringBuilder preview = new StringBuilder();
        String risk = "WRITE";
        for (Map<String, Object> planStep : state.taskPlan()) {
            Map<String, Object> copy = new LinkedHashMap<>(planStep);
            TaskStep runStep = byNo.get(stepNoOf(planStep, resolvedPlan.size()));
            if (runStep != null) {
                JsonNode args = resolveArgs(runStep, byNo);
                copy.put("args", JsonUtil.mapper().convertValue(args, Map.class));
                if (runStep.write()) {
                    ConfirmationPolicy.Decision decision =
                            confirmationPolicy.check(runStep.getToolName(), UserContext.role());
                    if (!decision.allowed()) {
                        return error(decision.message());
                    }
                    risk = decision.definition().sideEffect().name();
                    Map<String, Object> argsMap = JsonUtil.mapper().convertValue(args, Map.class);
                    pendingSteps.add(new ConfirmationService.PendingWriteStep(
                            runStep.getStepNo(), runStep.getToolName(), argsMap));
                    preview.append("\n").append(pendingSteps.size()).append(". ")
                            .append(decision.definition().description())
                            .append("，参数：").append(JsonUtil.toJson(argsMap));
                }
            }
            resolvedPlan.add(copy);
        }
        if (pendingSteps.size() != writeSteps.size()) {
            return error("待确认写步骤与任务步骤不一致，已拒绝执行");
        }

        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("tool", pendingSteps.size() == 1
                ? pendingSteps.get(0).tool() : "MULTI_WRITE");
        pending.put("args", pendingSteps.get(0).args());
        List<Map<String, Object>> viewSteps = new ArrayList<>();
        for (ConfirmationService.PendingWriteStep step : pendingSteps) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("stepNo", step.stepNo());
            view.put("tool", step.tool());
            view.put("args", step.args());
            viewSteps.add(view);
        }
        pending.put("steps", viewSteps);

        TaskPlanValidator.PlanValidationResult validation =
                planValidator.validateConfirm(resolvedPlan, pending);
        if (!validation.valid()) {
            return error(validation.message());
        }

        String summary = "即将执行 " + pendingSteps.size() + " 个写操作：" + preview;
        log.info("[task-confirm] creating batch token, runTail={}, writes={}, conversation={}",
                idTail(runId), pendingSteps.size(), state.conversationId());
        try {
            ConfirmationService.PendingAction action = confirmationService.create(
                    state.userId(), state.conversationId(), state.model(), runId,
                    resolvedPlan, summary, risk, pendingSteps);
            boolean waiting = taskRunService.markWaitingConfirm(state.userId(), runId,
                    action.confirmationId(), taskRunService.planHash(JsonUtil.toJson(resolvedPlan)));
            if (!waiting) {
                // 令牌已经写入 Redis，但任务已被取消或状态被并发推进：撤销令牌，
                // 不把一张无法执行（或可能执行错任务）的确认卡片返回给用户。
                confirmationService.finish(state.conversationId(), action.confirmationId());
                log.warn("[task-confirm] run status changed before waiting, runTail={}",
                        idTail(runId));
                return error("任务状态已变化，请重新发起操作");
            }

            Map<String, Object> card = new LinkedHashMap<>();
            card.put("confirmationId", action.confirmationId());
            card.put("runId", runId);
            card.put("tool", action.tool());
            card.put("args", pendingSteps.get(0).args());
            card.put("steps", viewSteps);
            card.put("summary", summary);
            card.put("risk", risk);
            card.put("expiresAt", action.expiresAt());

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.TASK_CONFIRMATION_ID, action.confirmationId());
            updates.put(WorkflowState.TASK_RUN_STATUS, TaskRun.Status.WAITING_CONFIRM.name());
            updates.put(WorkflowState.TASK_PENDING_ACTION, card);
            updates.put(WorkflowState.RESULT,
                    summary + "\n\n该操作会修改业务数据，请确认后执行。");
            updates.put(WorkflowState.TASK_CONFIDENCE, 1.0);
            updates.put(WorkflowState.TASK_CONFIDENCE_REASON, "等待用户确认写操作");
            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-confirm] batch token created, idTail={}, writes={}, runTail={}",
                    idTail(action.confirmationId()), pendingSteps.size(), idTail(runId));
            return updates;
        } catch (BizException e) {
            log.warn("[task-confirm] create batch token failed: {}", e.getMessage());
            return error("确认请求创建失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[task-confirm] unexpected error", e);
            return error("确认请求创建失败: " + e.getMessage());
        }
    }

    private JsonNode resolveArgs(TaskStep step, Map<Integer, TaskStep> byNo) {
        String argsJson = step.getArgsJson();
        JsonNode args;
        try {
            args = argsJson == null || argsJson.isBlank()
                    ? JsonUtil.mapper().createObjectNode()
                    : JsonUtil.mapper().readTree(argsJson);
        } catch (Exception e) {
            args = JsonUtil.mapper().createObjectNode();
        }
        return argsResolver.resolve(args, byNo);
    }

    private int stepNoOf(Map<String, Object> step, int fallback) {
        Object value = step.get("stepNo");
        return value instanceof Number number ? number.intValue() : fallback;
    }

    /** 旧版单写确认：服务没有任务运行信息时使用。 */
    private Map<String, Object> applyLegacy(WorkflowState state) {
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
