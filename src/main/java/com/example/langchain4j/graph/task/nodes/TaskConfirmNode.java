package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.model.TaskOperation;
import com.example.langchain4j.tool.api.constant.ToolNames;
import com.example.langchain4j.tool.confirmation.ConfirmationService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 写操作确认节点：不执行工具，只生成一次性确认令牌和确认话术。
 *
 * 一个确认令牌可以包含多步写计划（每步独立幂等键），但用户只确认一次；
 * 真正的执行发生在 POST /api/agent/confirm 之后，由确认执行链路携带 approved=true 完成。
 */
@Slf4j
@Component
public class TaskConfirmNode implements NodeAction<WorkflowState> {

    private final ConfirmationService confirmationService;

    public TaskConfirmNode(ConfirmationService confirmationService) {
        this.confirmationService = confirmationService;
    }

    /**
     * 为写操作创建确认动作，并透出 confirmationId 与逐步参数摘要。
     * 令牌创建失败时清空确认卡片，路由转到兜底节点。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        if (TaskOperation.parse(state.taskOperation()) != TaskOperation.WRITE) {
            return error("确认节点收到非 WRITE 操作，已拒绝");
        }
        List<ConfirmationService.PendingAction.Step> steps = resolveSteps(state);
        if (steps.isEmpty()) {
            return error("没有可执行的写操作计划，请补充要修改的记录");
        }
        log.info("[task-confirm] creating token, steps={}, firstTool={}, conversation={}",
                steps.size(), steps.get(0).tool(), state.conversationId());

        try {
            ConfirmationService.PendingAction action = confirmationService.create(
                    state.userId(), state.conversationId(), state.model(), steps, "WRITE");

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("confirmationId", action.confirmationId());
            view.put("tool", action.tool());
            view.put("args", action.args());
            view.put("summary", action.summary());
            view.put("risk", "WRITE");
            view.put("expiresAt", action.expiresAt());
            view.put("steps", stepViews(action.steps()));

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.TASK_CONFIRMATION_ID, action.confirmationId());
            updates.put(WorkflowState.TASK_PENDING_ACTION, view);
            updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, true);
            updates.put(WorkflowState.RESULT, action.summary() + "\n\n请确认是否继续。");
            updates.put(WorkflowState.TASK_CONFIDENCE, 1.0);
            updates.put(WorkflowState.TASK_CONFIDENCE_REASON, "等待用户确认写操作");
            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-confirm] token created, steps={}, idTail={}, conversation={}",
                    action.steps().size(), idTail(action.confirmationId()), state.conversationId());
            return updates;
        } catch (BizException e) {
            log.warn("[task-confirm] create token failed: {}", e.getMessage());
            return error("确认请求创建失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[task-confirm] unexpected error", e);
            return error("确认请求创建失败: " + e.getMessage());
        }
    }

    /**
     * 从状态中解析待确认步骤。
     *
     * 优先使用 TaskResolveNode 产出的 taskPlan；为空时用 taskArgs 兜底成单步，
     * 兼容旧 checkpoint 或旧模型输出。只接受白名单写工具，防止模型把只读/未知工具混入写计划。
     */
    private List<ConfirmationService.PendingAction.Step> resolveSteps(WorkflowState state) {
        List<ConfirmationService.PendingAction.Step> steps = new ArrayList<>();
        for (Map<String, Object> item : state.taskPlan()) {
            String tool = text(item.get("tool"));
            if (!ToolNames.COMPLETE_DEMO_RECORD.equals(tool)) {
                log.warn("[task-confirm] skip non-write tool in plan: {}", tool);
                continue;
            }
            Map<String, Object> args = map(item.get("args"));
            if (args.isEmpty()) {
                continue;
            }
            steps.add(new ConfirmationService.PendingAction.Step(steps.size() + 1, tool, args,
                    text(item.get("summary"))));
        }
        if (steps.isEmpty() && !state.taskArgs().isEmpty()) {
            steps.add(new ConfirmationService.PendingAction.Step(1,
                    ToolNames.COMPLETE_DEMO_RECORD, state.taskArgs(), ""));
        }
        return steps;
    }

    /** 确认卡片步骤视图：前端可逐步展示，不需要再解析内部对象。 */
    private List<Map<String, Object>> stepViews(
            List<ConfirmationService.PendingAction.Step> steps) {
        List<Map<String, Object>> views = new ArrayList<>(steps.size());
        for (ConfirmationService.PendingAction.Step step : steps) {
            Map<String, Object> view = new LinkedHashMap<>();
            view.put("stepNo", step.stepNo());
            view.put("tool", step.tool());
            view.put("args", step.args());
            view.put("summary", step.summary().isBlank()
                    ? "执行 " + step.tool() + "，参数 " + JsonUtil.toJson(step.args())
                    : step.summary());
            views.add(view);
        }
        return views;
    }

    /** 对象转字符串；null 返回空串。 */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 对象转字符串键 Map；类型不符返回空 Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
    }

    /** 失败时同时清空待确认卡片，避免响应透出没有 confirmationId 的半成品。 */
    private Map<String, Object> error(String message) {
        return Map.of(
                WorkflowState.ERROR, message,
                WorkflowState.TASK_PENDING_ACTION, Map.of(),
                WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
    }

    /** 日志脱敏：只输出确认 ID 末尾 8 位。 */
    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
