package com.example.langchain4j.tool.confirmation;

import com.example.langchain4j.graph.common.nodes.FinalizeNode;
import com.example.langchain4j.graph.qa.model.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.model.TaskOperation;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.graph.task.nodes.TaskWriteNode;
import com.example.langchain4j.tool.run.TaskRunService;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确认后执行编排：直接执行已确认的写计划，再生成回答并收尾。
 *
 * 不重新走意图识别、读写分类和参数抽取，避免“用户确认 A、系统执行 B”。
 * 执行事实以 {@code ai_task_run} + {@code ai_tool_execution} 为准；这里只按顺序调用
 * 写节点、回答节点和收尾节点，并把运行最终状态写回台账。
 */
@Slf4j
@Service
public class TaskConfirmService {

    private final TaskWriteNode taskWriteNode;
    private final TaskAnswerNode taskAnswerNode;
    private final FinalizeNode finalizeNode;
    private final TaskRunService taskRunService;

    public TaskConfirmService(TaskWriteNode taskWriteNode,
                              TaskAnswerNode taskAnswerNode,
                              FinalizeNode finalizeNode,
                              TaskRunService taskRunService) {
        this.taskWriteNode = taskWriteNode;
        this.taskAnswerNode = taskAnswerNode;
        this.finalizeNode = finalizeNode;
        this.taskRunService = taskRunService;
    }

    /**
     * 执行一次已确认写计划：写节点（按步骤顺序）→ 回答节点 → 收尾节点。
     *
     * 入参只带令牌中的固定步骤计划，不携带任何重新规划入口；节点异常原样向上抛出。
     * 每步独立事务提交，前面步骤成功不会因为后面失败而回滚；失败进度写入运行台账。
     */
    public WorkflowState execute(ConfirmationService.PendingAction action,
                                 String userId, String query) {
        long start = System.currentTimeMillis();
        WorkflowState state = new WorkflowState(initialState(action, userId, query));
        log.info("[task-confirm] start, conversation={}, confirmationTail={}, tool={}, argKeys={}",
                action.conversationId(), tail(action.confirmationId()), action.tool(),
                action.args().keySet());

        // 进入执行中：允许从上次失败步骤重试；已完成步骤由幂等闸门直接重放
        if (!taskRunService.beginRun(userId, action.confirmationId())) {
            throw new BizException(ErrorCode.CONFIRMATION_IN_PROGRESS);
        }
        state = merge(state, taskWriteNode.apply(state));
        state = merge(state, taskAnswerNode.apply(state));
        state = merge(state, finalizeNode.apply(state));
        // 运行台账以最终答案和逐步轨迹收尾；写失败但回答节点正常返回时也必须如实记录
        recordRunResult(action, userId, state);

        log.info("[task-confirm] done, conversation={}, confirmationTail={}, resultLength={}, costMs={}",
                action.conversationId(), tail(action.confirmationId()),
                state.result() == null ? 0 : state.result().length(),
                System.currentTimeMillis() - start);
        return state;
    }

    /** 构造确认执行入参：固定 WRITE 操作、多步计划、已确认标记和 confirmationId 幂等键。 */
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
        inputs.put(WorkflowState.TASK_PLAN, plan(action.steps()));
        inputs.put(WorkflowState.TASK_APPROVED, true);
        inputs.put(WorkflowState.TASK_IDEMPOTENCY_KEY, action.confirmationId());
        inputs.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
        inputs.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
        return inputs;
    }

    /**
     * 把确认令牌里的步骤计划展开为图状态结构。
     * stepNo 由令牌统一重排，执行器直接按顺序消费。
     */
    private List<Map<String, Object>> plan(List<ConfirmationService.PendingAction.Step> steps) {
        List<Map<String, Object>> plan = new ArrayList<>(steps.size());
        for (ConfirmationService.PendingAction.Step step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("stepNo", step.stepNo());
            item.put("tool", step.tool());
            item.put("args", step.args());
            item.put("summary", step.summary());
            plan.add(item);
        }
        return plan;
    }

    /**
     * 写回运行最终状态。
     *
     * 全部步骤成功 → SUCCEEDED，结果保存最终回答供重放；
     * 任一步失败 → 按已完成步数记 FAILED（0 步）或 PARTIAL_FAILED，
     * current_step 指向失败步骤，便于用户重试时从该步接续。
     */
    private void recordRunResult(ConfirmationService.PendingAction action, String userId,
                                 WorkflowState state) {
        String runId = action.confirmationId();
        String result = Texts.truncate(state.result(), 4000);
        if (state.error() == null || state.error().isBlank()) {
            taskRunService.markSucceeded(userId, runId, result);
            return;
        }
        int completed = 0;
        int failedStep = 1;
        String errorCode = "WRITE_FAILED";
        String errorMessage = state.error();
        for (Map<String, Object> item : state.taskTrace()) {
            boolean success = Boolean.parseBoolean(String.valueOf(item.get("success")));
            if (!success) {
                failedStep = intOf(item.get("stepNo"), completed + 1);
                errorCode = Texts.textOr(item.get("errorCode"), errorCode);
                errorMessage = Texts.textOr(item.get("summary"), errorMessage);
                break;
            }
            completed++;
        }
        taskRunService.markFailed(userId, runId, completed, failedStep, errorCode, errorMessage,
                result);
    }

    /** 台账里的 stepNo 可能是数字或字符串；统一安全转 int。 */
    private int intOf(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return fallback;
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

    /** 日志脱敏：只输出确认 ID 末尾 8 位。 */
    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
