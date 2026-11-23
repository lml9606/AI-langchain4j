package com.example.langchain4j.service;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.repository.entity.TaskStep;
import com.example.langchain4j.repository.mapper.TaskRunMapper;
import com.example.langchain4j.repository.mapper.TaskStepMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * 任务运行服务：任务/步骤的持久化与状态机。
 *
 * <p>设计原则：
 * <ul>
 *   <li>任务状态只在这里流转，节点和 worker 不直接改表；</li>
 *   <li>写步骤台账与业务写同事务由 WriteExecutionTemplate 保证，本服务只维护步骤状态；</li>
 *   <li>游标只前进不后退，失败重试从失败步骤开始，不重跑已成功步骤。</li>
 * </ul>
 */
@Slf4j
@Service
public class TaskRunService {

    private final TaskRunMapper runMapper;
    private final TaskStepMapper stepMapper;

    public TaskRunService(TaskRunMapper runMapper, TaskStepMapper stepMapper) {
        this.runMapper = runMapper;
        this.stepMapper = stepMapper;
    }

    /**
     * 创建任务和全部步骤。
     *
     * @param plan 已通过 {@code TaskPlanValidator} 校验的计划，每步含 tool/args/purpose/sideEffect
     */
    @Transactional
    public TaskRun createRun(String conversationId, String userId, String workflowId,
                             List<Map<String, Object>> plan) {
        if (plan == null || plan.isEmpty()) {
            throw new IllegalArgumentException("任务计划不能为空");
        }
        String planJson = JsonUtil.toJson(plan);
        TaskRun run = new TaskRun();
        run.setId("run_" + UUID.randomUUID().toString().replace("-", ""));
        run.setConversationId(conversationId);
        run.setUserId(userId);
        run.setWorkflowId(workflowId == null ? "" : workflowId);
        run.setStatus(TaskRun.Status.PLANNING);
        run.setCurrentStep(0);
        run.setReplySaved(false);
        run.setPlanJson(planJson);
        run.setPlanHash(sha256(planJson));
        runMapper.insert(run);

        List<TaskStep> steps = new ArrayList<>(plan.size());
        for (int index = 0; index < plan.size(); index++) {
            Map<String, Object> item = plan.get(index);
            TaskStep step = new TaskStep();
            step.setRunId(run.getId());
            step.setStepNo(index);
            step.setStageId(stringOf(item.get("stage")));
            step.setToolName(stringOf(item.get("tool")));
            step.setSideEffect(stringOf(item.get("sideEffect")));
            step.setArgsJson(JsonUtil.toJson(item.get("args") == null ? Map.of() : item.get("args")));
            step.setPurpose(stringOf(item.get("purpose")));
            step.setStatus(TaskStep.Status.PENDING);
            steps.add(step);
        }
        stepMapper.insertBatch(steps);
        log.info("[task-run] created, runTail={}, workflow={}, steps={}, conversation={}",
                idTail(run.getId()), workflowId, steps.size(), conversationId);
        return run;
    }

    public Optional<TaskRun> find(String userId, String runId) {
        if (isBlank(userId) || isBlank(runId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(runMapper.selectById(userId, runId));
    }

    public Optional<TaskRun> findByConfirmationId(String userId, String confirmationId) {
        if (isBlank(userId) || isBlank(confirmationId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(runMapper.selectByConfirmationId(userId, confirmationId));
    }

    public List<TaskStep> steps(String runId) {
        if (isBlank(runId)) {
            return List.of();
        }
        return stepMapper.selectByRunId(runId);
    }

    /** 只读阶段执行完、写步骤已生成确认卡片：任务进入待确认。 */
    @Transactional
    public boolean markWaitingConfirm(String userId, String runId, String confirmationId,
                                      String planHash) {
        return runMapper.markWaitingConfirm(userId, runId, confirmationId, planHash) == 1;
    }

    /** 用户确认（或恢复执行）后进入 RUNNING；终态任务不会被打回。 */
    @Transactional
    public boolean markRunning(String userId, String runId, String confirmationId) {
        return runMapper.markRunning(userId, runId, confirmationId) == 1;
    }

    @Transactional
    public void finish(String userId, String runId, TaskRun.Status status, int currentStep,
                       String errorCode, String errorMessage) {
        runMapper.updateStatus(userId, runId, status.name(), currentStep,
                nullToEmpty(errorCode), nullToEmpty(errorMessage));
        log.info("[task-run] finished, runTail={}, status={}, currentStep={}, reason={}",
                idTail(runId), status, currentStep, errorMessage);
    }

    @Transactional
    public void advance(String userId, String runId, int nextStep) {
        runMapper.advanceCursor(userId, runId, nextStep);
    }

    /** 保存最终回答：确认令牌过期后，重放仍能返回同一份结果。 */
    @Transactional
    public void saveResult(String userId, String runId, String result) {
        runMapper.saveResult(userId, runId, result);
    }

    /** 标记会话回答已补偿；重复调用幂等。 */
    @Transactional
    public void markReplySaved(String userId, String runId) {
        runMapper.markReplySaved(userId, runId);
    }

    @Transactional
    public void cancelByConversation(String userId, String conversationId) {
        int changed = runMapper.markCancelledByConversation(userId, conversationId);
        if (changed > 0) {
            // run 状态落定后再取消步骤：SQL 子查询按 CANCELLED 过滤，顺序不能反。
            stepMapper.markCancelledByConversation(userId, conversationId);
            log.info("[task-run] cancelled by new message, conversation={}, count={}",
                    conversationId, changed);
        }
    }

    /**
     * 用户主动取消确认：只允许取消仍处于 PLANNING / WAITING_CONFIRM 的任务。
     * 已进入 RUNNING 的任务可能正在写业务数据，不能中途改状态；由执行器按步骤失败策略收敛。
     *
     * @return true 表示任务确实从待确认状态流转为 CANCELLED
     */
    @Transactional
    public boolean cancel(String userId, String runId) {
        if (isBlank(userId) || isBlank(runId)) {
            return false;
        }
        int changed = runMapper.markCancelled(userId, runId);
        if (changed == 0) {
            return false;
        }
        stepMapper.markCancelledByRun(runId);
        log.info("[task-run] cancelled by user, runTail={}", idTail(runId));
        return true;
    }

    /** 多实例执行租约；返回 true 表示当前实例可以推进该任务。 */
    public boolean tryAcquireLease(String runId, String owner, int leaseSeconds) {
        return runMapper.acquireLease(runId, owner, Math.max(5, leaseSeconds)) == 1;
    }

    /** 续租失败说明执行权已被其他实例接管，调用方必须停止推进。 */
    public boolean renewLease(String runId, String owner, int leaseSeconds) {
        return runMapper.renewLease(runId, owner, Math.max(5, leaseSeconds)) == 1;
    }

    public void releaseLease(String runId, String owner) {
        try {
            runMapper.releaseLease(runId, owner);
        } catch (RuntimeException e) {
            // 租约到期会自然失效；释放失败只影响下次接管的等待时间
            log.warn("[task-run] release lease failed, runTail={}: {}",
                    idTail(runId), e.getMessage());
        }
    }

    public List<TaskRun> findLeaseExpired(int limit) {
        return runMapper.findLeaseExpired(Math.max(1, limit));
    }

    public List<TaskRun> findUnreplied(int limit) {
        return runMapper.findUnreplied(Math.max(1, limit));
    }

    // ---- 步骤状态：集中封装，避免执行器散落 SQL 语义 ----

    public boolean markStepRunning(String runId, int stepNo) {
        return stepMapper.markRunning(runId, stepNo) == 1;
    }

    public void updateStepArgs(String runId, int stepNo, String argsJson) {
        stepMapper.updateArgs(runId, stepNo, argsJson);
    }

    public void markStepSucceeded(String runId, int stepNo, String summary, String resultJson,
                                  String evidenceJson, long costMs) {
        stepMapper.markSucceeded(runId, stepNo, summary, resultJson, evidenceJson, costMs);
    }

    public void markStepFailed(String runId, int stepNo, String errorCode, String errorMessage,
                               long costMs) {
        stepMapper.markFailed(runId, stepNo, errorCode, errorMessage, costMs);
    }

    public void markRemainingSkipped(String runId, int fromStepNo) {
        stepMapper.markSkipped(runId, fromStepNo);
    }

    /** 失败重试前恢复上一轮被跳过的后续步骤，保证可以继续执行整条计划。 */
    public void resetSkipped(String runId) {
        stepMapper.resetSkipped(runId);
    }

    public void markStepsCancelled(String runId) {
        stepMapper.markCancelledByRun(runId);
    }

    /** 参数指纹：确认后计划被篡改时，执行前能发现。 */
    public String planHash(String planJson) {
        return sha256(planJson);
    }

    private String sha256(String text) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private String stringOf(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private String idTail(String id) {
        if (isBlank(id)) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
