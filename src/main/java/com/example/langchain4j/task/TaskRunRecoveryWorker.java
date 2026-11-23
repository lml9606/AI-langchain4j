package com.example.langchain4j.task;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.service.ConversationService;
import com.example.langchain4j.service.TaskRunService;
import com.example.langchain4j.tool.runtime.TaskRunExecutor;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 任务崩溃恢复 worker。
 *
 * <p>场景：用户确认后进程在写步骤执行途中崩溃，数据库里 run 仍是 RUNNING，
 * 但对应的会话请求已经结束。租约到期后由本 worker 接管，从 currentStep 继续执行。
 *
 * <p>幂等保证：
 * <ul>
 *   <li>租约保证同一时刻只有一个实例推进同一条 run；</li>
 *   <li>已 SUCCEEDED 的步骤直接跳过；</li>
 *   <li>写步骤使用 {@code runId:stepNo}，重复调用只会重放台账结果。</li>
 * </ul>
 *
 * <p>恢复完成后会补偿会话助手消息；reply_saved 保证同一终态最多补发一次。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent.task.recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TaskRunRecoveryWorker {

    private final TaskRunService runService;
    private final TaskRunExecutor runExecutor;
    private final AgentProperties properties;
    private final TaskAnswerNode taskAnswerNode;
    private final ConversationService conversationService;

    public TaskRunRecoveryWorker(TaskRunService runService, TaskRunExecutor runExecutor,
                                 AgentProperties properties, TaskAnswerNode taskAnswerNode,
                                 ConversationService conversationService) {
        this.runService = runService;
        this.runExecutor = runExecutor;
        this.properties = properties;
        this.taskAnswerNode = taskAnswerNode;
        this.conversationService = conversationService;
    }

    @Scheduled(fixedDelayString = "${agent.task.recovery.interval-ms:30000}")
    public void recover() {
        AgentProperties.Task.Recovery recovery = properties.getTask().getRecovery();
        List<TaskRun> runs = runService.findLeaseExpired(recovery.getBatchSize());
        if (!runs.isEmpty()) {
            log.info("[task-recovery] found {} expired runs", runs.size());
            for (TaskRun run : runs) {
                try {
                    TaskRunExecutor.TaskRunResult result =
                            runExecutor.execute(run.getUserId(), run.getId(), true);
                    saveRecoveryReply(result);
                } catch (RuntimeException e) {
                    // 单条任务恢复失败不能影响其他任务；租约到期后会再次被扫描到
                    log.error("[task-recovery] resume failed, runTail={}",
                            idTail(run.getId()), e);
                }
            }
        }
        for (TaskRun run : runService.findUnreplied(recovery.getBatchSize())) {
            try {
                saveRecoveryReply(runExecutor.describe(run.getUserId(), run.getId()));
            } catch (RuntimeException e) {
                log.error("[task-recovery] reply compensation failed, runTail={}",
                        idTail(run.getId()), e);
            }
        }
    }

    /**
     * 把任务终态补偿成会话助手消息。
     * <p>幂等：run.reply_saved 是最终标记；如果会话最后一条已经是助手消息，直接标记完成；
     * 如果用户已经开启新话题（最后一条不是本任务的“确认执行”用户消息），不把旧任务答案插进新轮次。
     */
    private void saveRecoveryReply(TaskRunExecutor.TaskRunResult result) {
        if (result == null || result.busy() || result.run() == null
                || result.run().isReplySaved()) {
            return;
        }
        TaskRun run = result.run();
        Message latest = conversationService.latestMessage(run.getConversationId());
        if (latest != null && Message.ROLE_ASSISTANT.equals(latest.getRole())) {
            runService.markReplySaved(run.getUserId(), run.getId());
            return;
        }
        boolean confirmationTurn = latest == null
                || (Message.ROLE_USER.equals(latest.getRole())
                && latest.getContent() != null && latest.getContent().startsWith("确认执行："));
        if (!confirmationTurn) {
            log.info("[task-recovery] skip stale reply, runTail={}, conversation={}",
                    idTail(run.getId()), run.getConversationId());
            runService.markReplySaved(run.getUserId(), run.getId());
            return;
        }

        String model = properties.getLlm().getDefaultModel();
        Integer tokenUsage = null;
        String answer = run.getResult();
        if (answer == null || answer.isBlank()) {
            Map<String, Object> updates = taskAnswerNode.apply(recoveryState(run, result));
            Object value = updates.get(WorkflowState.RESULT);
            answer = value == null ? "" : String.valueOf(value);
            Object tokens = updates.get(WorkflowState.TOKEN_USAGE);
            tokenUsage = tokens instanceof Number number ? number.intValue() : null;
        }
        if (answer.isBlank()) {
            answer = "任务已执行完成，请通过任务进度接口查看结果。";
        }
        conversationService.saveMessage(run.getConversationId(), Message.ROLE_ASSISTANT,
                answer, model, tokenUsage, null, "task-reply:" + run.getId());
        runService.saveResult(run.getUserId(), run.getId(), answer);
        runService.markReplySaved(run.getUserId(), run.getId());
        log.info("[task-recovery] reply compensated, runTail={}, conversation={}, resultLength={}",
                idTail(run.getId()), run.getConversationId(), answer.length());
    }

    /** 用任务台账 + 步骤轨迹构造回答节点需要的状态，不重新执行任何工具。 */
    private WorkflowState recoveryState(TaskRun run, TaskRunExecutor.TaskRunResult result) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(WorkflowState.QUERY, "确认执行任务：" + run.getWorkflowId());
        data.put(WorkflowState.MODEL, properties.getLlm().getDefaultModel());
        data.put(WorkflowState.USER_ID, run.getUserId());
        data.put(WorkflowState.CONVERSATION_ID, run.getConversationId());
        data.put(WorkflowState.INTENT, QaIntent.TASK);
        data.put(WorkflowState.TASK_APPROVED, true);
        data.put(WorkflowState.TASK_RUN_ID, run.getId());
        data.put(WorkflowState.TASK_RUN_STATUS, run.getStatus().name());
        data.put(WorkflowState.TASK_WORKFLOW_ID, run.getWorkflowId());
        data.put(WorkflowState.TASK_PLAN, parsePlan(run.getPlanJson()));
        data.put(WorkflowState.TASK_TRACE, result.trace());
        data.put(WorkflowState.TASK_EVIDENCE, result.evidence());
        return new WorkflowState(data);
    }

    private List<Map<String, Object>> parsePlan(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> plan = JsonUtil.fromJson(planJson,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            return plan == null ? List.of() : plan;
        } catch (Exception e) {
            log.warn("[task-recovery] plan parse failed: {}", e.getMessage());
            return List.of();
        }
    }

    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
