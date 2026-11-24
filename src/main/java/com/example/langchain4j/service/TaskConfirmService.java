package com.example.langchain4j.service;

import com.example.langchain4j.graph.common.nodes.FinalizeNode;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.TaskOperation;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.graph.task.nodes.TaskWriteNode;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确认后执行编排：直接执行已确认的单个写工具，再生成回答并收尾。
 *
 * 不重新走意图识别、读写分类和参数抽取，避免“用户确认 A、系统执行 B”。
 * 执行事实以 {@code ai_tool_execution} 为准；这里只按顺序调用写节点、回答节点和收尾节点。
 */
@Slf4j
@Service
public class TaskConfirmService {

    private final TaskWriteNode taskWriteNode;
    private final TaskAnswerNode taskAnswerNode;
    private final FinalizeNode finalizeNode;

    public TaskConfirmService(TaskWriteNode taskWriteNode,
                              TaskAnswerNode taskAnswerNode,
                              FinalizeNode finalizeNode) {
        this.taskWriteNode = taskWriteNode;
        this.taskAnswerNode = taskAnswerNode;
        this.finalizeNode = finalizeNode;
    }

    /**
     * 执行一条已确认写操作：写节点 → 回答节点 → 收尾节点。
     *
     * 入参只带令牌中的固定工具和参数，不携带任何重新规划入口；节点异常原样向上抛出。
     */
    public WorkflowState execute(ConfirmationService.PendingAction action,
                                 String userId, String query) {
        long start = System.currentTimeMillis();
        WorkflowState state = new WorkflowState(initialState(action, userId, query));
        log.info("[task-confirm] start, conversation={}, confirmationTail={}, tool={}, argKeys={}",
                action.conversationId(), tail(action.confirmationId()), action.tool(),
                action.args().keySet());

        state = merge(state, taskWriteNode.apply(state));
        state = merge(state, taskAnswerNode.apply(state));
        state = merge(state, finalizeNode.apply(state));

        log.info("[task-confirm] done, conversation={}, confirmationTail={}, resultLength={}, costMs={}",
                action.conversationId(), tail(action.confirmationId()),
                state.result() == null ? 0 : state.result().length(),
                System.currentTimeMillis() - start);
        return state;
    }

    /** 构造确认执行入参：固定 WRITE 操作、已确认标记和 confirmationId 幂等键。 */
    private Map<String, Object> initialState(ConfirmationService.PendingAction action,
                                             String userId, String query) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(WorkflowState.QUERY, query);
        inputs.put(WorkflowState.MODEL, action.model());
        inputs.put(WorkflowState.USER_ID, userId);
        inputs.put(WorkflowState.CONVERSATION_ID, action.conversationId());
        // 置信度透出依赖 intent=task；确认链路不跑 IntentPlanNode，这里显式补齐。
        inputs.put(WorkflowState.INTENT, QaIntent.TASK);
        inputs.put(WorkflowState.TASK_OPERATION, TaskOperation.WRITE.name());
        inputs.put(WorkflowState.TASK_ARGS, action.args());
        inputs.put(WorkflowState.TASK_APPROVED, true);
        inputs.put(WorkflowState.TASK_IDEMPOTENCY_KEY, action.confirmationId());
        inputs.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
        inputs.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
        return inputs;
    }

    /** 节点返回增量 Map，合并时复制原状态，保持与图内不可变更新一致的语义。 */
    private WorkflowState merge(WorkflowState state, Map<String, Object> updates) {
        Map<String, Object> data = new LinkedHashMap<>(state.data());
        if (updates != null) {
            data.putAll(updates);
        }
        return new WorkflowState(data);
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
