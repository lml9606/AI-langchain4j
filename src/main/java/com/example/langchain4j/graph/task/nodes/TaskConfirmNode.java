package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.TaskOperation;
import com.example.langchain4j.graph.task.TaskTools;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 写操作确认节点：不执行工具，只生成一次性确认令牌和确认话术。
 *
 * 当前写工具只是流程骨架，这里不重复做角色、风险或工具策略校验。
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
     * 为写操作创建单次确认动作，并透出 confirmationId 与参数摘要。
     * 令牌创建失败时清空确认卡片，路由转到兜底节点。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        if (TaskOperation.parse(state.taskOperation()) != TaskOperation.WRITE) {
            return error("确认节点收到非 WRITE 操作，已拒绝");
        }
        Map<String, Object> args = state.taskArgs();
        String summary = "即将修改演示记录，参数：" + JsonUtil.toJson(args) + "。";
        log.info("[task-confirm] creating token, tool={}, argKeys={}, conversation={}",
                TaskTools.WRITE, args.keySet(), state.conversationId());

        try {
            ConfirmationService.PendingAction action = confirmationService.create(
                    state.userId(), state.conversationId(), state.model(),
                    TaskTools.WRITE, args, summary, "WRITE");

            Map<String, Object> view = new LinkedHashMap<>();
            view.put("confirmationId", action.confirmationId());
            view.put("tool", TaskTools.WRITE);
            view.put("args", args);
            view.put("summary", summary);
            view.put("risk", "WRITE");
            view.put("expiresAt", action.expiresAt());

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.TASK_CONFIRMATION_ID, action.confirmationId());
            updates.put(WorkflowState.TASK_PENDING_ACTION, view);
            updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, true);
            updates.put(WorkflowState.RESULT, summary + "\n\n请确认是否继续。");
            updates.put(WorkflowState.TASK_CONFIDENCE, 1.0);
            updates.put(WorkflowState.TASK_CONFIDENCE_REASON, "等待用户确认写操作");
            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-confirm] token created, tool={}, idTail={}, conversation={}",
                    TaskTools.WRITE, idTail(action.confirmationId()), state.conversationId());
            return updates;
        } catch (BizException e) {
            log.warn("[task-confirm] create token failed: {}", e.getMessage());
            return error("确认请求创建失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[task-confirm] unexpected error", e);
            return error("确认请求创建失败: " + e.getMessage());
        }
    }

    /** 失败时同时清空待确认卡片，避免响应透出没有 confirmationId 的半成品。 */
    private Map<String, Object> error(String message) {
        return Map.of(
                WorkflowState.ERROR, message,
                WorkflowState.TASK_PENDING_ACTION, Map.of(),
                WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
    }

    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}