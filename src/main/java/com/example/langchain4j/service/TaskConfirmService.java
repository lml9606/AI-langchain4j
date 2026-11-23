package com.example.langchain4j.service;

import com.example.langchain4j.audit.AuditEntry;
import com.example.langchain4j.audit.AuditPayloads;
import com.example.langchain4j.audit.AuditRecorder;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.common.nodes.FinalizeNode;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.graph.task.nodes.TaskExecuteNode;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确认后执行编排：按已确认计划推进任务，再生成回答并收尾。
 *
 * <p>不重新走意图识别和任务规划，避免“用户确认 A、系统执行 B”。
 * 执行事实仍以 {@code ai_task_run} / {@code ai_task_step} 为准；
 * 这里只负责按顺序调用执行、回答、收尾三个节点，不再构建 LangGraph 子图。
 */
@Slf4j
@Service
public class TaskConfirmService {

    private static final String NODE_TASK_EXECUTE = "task_execute";
    private static final String NODE_TASK_ANSWER = "task_answer";
    private static final String NODE_FINALIZE = "finalize";

    private final TaskExecuteNode taskExecuteNode;
    private final TaskAnswerNode taskAnswerNode;
    private final FinalizeNode finalizeNode;
    private final AgentProperties properties;
    private final AuditRecorder auditRecorder;

    public TaskConfirmService(TaskExecuteNode taskExecuteNode,
                              TaskAnswerNode taskAnswerNode,
                              FinalizeNode finalizeNode,
                              AgentProperties properties,
                              @Qualifier("asyncAuditRecorder") AuditRecorder auditRecorder) {
        this.taskExecuteNode = taskExecuteNode;
        this.taskAnswerNode = taskAnswerNode;
        this.finalizeNode = finalizeNode;
        this.properties = properties;
        this.auditRecorder = auditRecorder;
    }

    /**
     * 执行一条已确认动作。
     *
     * @param action   Redis 令牌中的完整已确认计划
     * @param userId   当前用户（任务表与工具执行的数据隔离维度）
     * @param query    确认轮的用户问题（仅用于审计和回答上下文）
     * @return 执行、回答、收尾后的最终状态
     */
    public WorkflowState execute(ConfirmationService.PendingAction action,
                                 String userId, String query) {
        long start = System.currentTimeMillis();
        WorkflowState state = new WorkflowState(initialState(action, userId, query));
        log.info("[task-confirm] start, conversation={}, confirmationTail={}, runTail={}, " +
                        "planSteps={}, writeSteps={}", action.conversationId(),
                tail(action.confirmationId()), tail(action.runId()), action.plan().size(),
                action.steps().size());

        state = apply(state, NODE_TASK_EXECUTE, taskExecuteNode);
        state = apply(state, NODE_TASK_ANSWER, taskAnswerNode);
        state = apply(state, NODE_FINALIZE, finalizeNode);

        log.info("[task-confirm] done, conversation={}, confirmationTail={}, runTail={}, " +
                        "runStatus={}, resultLength={}, costMs={}",
                action.conversationId(), tail(action.confirmationId()), tail(action.runId()),
                state.taskRunStatus(), state.result() == null ? 0 : state.result().length(),
                System.currentTimeMillis() - start);
        return state;
    }

    /** 构造确认执行入参：只带已确认计划与授权标记，不携带任何重新规划入口。 */
    private Map<String, Object> initialState(ConfirmationService.PendingAction action,
                                             String userId, String query) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(WorkflowState.QUERY, query);
        inputs.put(WorkflowState.MODEL, action.model());
        inputs.put(WorkflowState.USER_ID, userId);
        inputs.put(WorkflowState.CONVERSATION_ID, action.conversationId());
        // 置信度透出依赖 intent=task；确认链路不跑 IntentPlanNode，这里显式补齐
        inputs.put(WorkflowState.INTENT, QaIntent.TASK);
        inputs.put(WorkflowState.TASK_PLAN, action.plan());
        action.plan().stream().findFirst()
                .map(step -> step.get("workflowId"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .ifPresent(value -> inputs.put(WorkflowState.TASK_WORKFLOW_ID,
                        String.valueOf(value)));
        inputs.put(WorkflowState.TASK_APPROVED, true);
        inputs.put(WorkflowState.TASK_IDEMPOTENCY_KEY, action.confirmationId());
        if (!action.runId().isBlank()) {
            inputs.put(WorkflowState.TASK_RUN_ID, action.runId());
            inputs.put(WorkflowState.TASK_RUN_STATUS, TaskRun.Status.RUNNING.name());
        }
        inputs.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
        inputs.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
        return inputs;
    }

    /** 顺序执行单个节点，并把节点增量合并进状态；审计失败不影响业务。 */
    private WorkflowState apply(WorkflowState state, String nodeName,
                                NodeAction<WorkflowState> node) {
        long start = System.currentTimeMillis();
        int maxLength = Math.max(1, properties.getLimits().getAuditNodeValueMaxLength());
        Map<String, Object> input = AuditPayloads.nodeInput(state, maxLength);
        try {
            Map<String, Object> updates = node.apply(state);
            String error = text(updates == null ? null : updates.get(WorkflowState.ERROR));
            safeRecord(AuditEntry.of(state.conversationId(), nodeName,
                    error.isBlank() ? GraphExecution.STATUS_SUCCESS : GraphExecution.STATUS_FAILED,
                    input, AuditPayloads.output(updates, maxLength),
                    error.isBlank() ? null : error,
                    System.currentTimeMillis() - start, rootCause(updates, state)));
            return merge(state, updates);
        } catch (Exception e) {
            safeRecord(AuditEntry.of(state.conversationId(), nodeName,
                    GraphExecution.STATUS_FAILED, input, Map.of(), e.getMessage(),
                    System.currentTimeMillis() - start, state.rootCause()));
            if (e instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw new IllegalStateException("Task confirm node failed: " + nodeName, e);
        }
    }

    /** 节点返回增量 Map，合并时复制原状态，保持与图内不可变更新一致的语义。 */
    private WorkflowState merge(WorkflowState state, Map<String, Object> updates) {
        Map<String, Object> data = new LinkedHashMap<>(state.data());
        if (updates != null) {
            data.putAll(updates);
        }
        return new WorkflowState(data);
    }

    /** 节点输出中的 rootCause 优先于执行前状态，保证本轮根因写入本轮审计。 */
    private String rootCause(Map<String, Object> output, WorkflowState state) {
        String current = text(output == null ? null : output.get(WorkflowState.ROOT_CAUSE));
        return current.isBlank() ? state.rootCause() : current;
    }

    /** 审计失败只告警，不能反向破坏写操作和回答链路。 */
    private void safeRecord(AuditEntry entry) {
        try {
            auditRecorder.record(entry);
        } catch (Exception e) {
            log.warn("[audit] task confirm audit failed, node={}, reason={}",
                    entry.nodeName(), e.getMessage());
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
