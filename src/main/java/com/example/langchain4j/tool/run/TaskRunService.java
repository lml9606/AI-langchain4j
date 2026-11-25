package com.example.langchain4j.tool.run;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.tool.execution.ToolExecution;
import com.example.langchain4j.tool.execution.ToolExecutionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * 多步写运行台账服务：把“运行”和“每步执行”作为同一份数据库事实维护。
 *
 * 创建 PENDING 时，运行记录和全部步骤台账在同一事务内提交，避免出现
 * “有确认令牌、只有部分步骤记录”的半成品；执行阶段每步独立事务，
 * 前面步骤提交后不回滚，失败步骤通过 run.current_step 精确定位。
 */
@Slf4j
@Service
public class TaskRunService {

    private final TaskRunMapper runMapper;
    private final ToolExecutionService toolExecutionService;
    private final AgentProperties properties;

    public TaskRunService(TaskRunMapper runMapper, ToolExecutionService toolExecutionService,
                          AgentProperties properties) {
        this.runMapper = runMapper;
        this.toolExecutionService = toolExecutionService;
        this.properties = properties;
    }

    /**
     * 步骤执行台账 ID：同一确认内每步必须有独立幂等键。
     * 单步与多步统一格式，避免两套逻辑。
     */
    public static String stepExecutionId(String runId, int stepNo) {
        return runId + "#" + stepNo;
    }

    /**
     * 创建待确认运行：run + 全部步骤 PENDING 台账原子提交。
     *
     * 任一步骤写入失败都会回滚整个事务，调用方据此撤销 Redis 令牌。
     */
    @Transactional(rollbackFor = Exception.class)
    public void createPending(TaskRun run, List<ToolExecution> stepExecutions) {
        if (runMapper.insertPending(run) == 0) {
            log.warn("[task-run] duplicate run id, runTail={}", tail(run.getRunId()));
            throw new BizException(ErrorCode.CONFIRMATION_PENDING, "确认动作已存在");
        }
        for (ToolExecution execution : stepExecutions) {
            if (!toolExecutionService.recordPending(execution)) {
                throw new BizException(ErrorCode.DB_UNAVAILABLE, "步骤台账保存失败");
            }
        }
        log.info("[task-run] pending created, runTail={}, steps={}, conversation={}",
                tail(run.getRunId()), run.getTotalSteps(), run.getConversationId());
    }

    /** 查询运行；令牌已删除时用它回答“到底执行到哪一步”。 */
    public Optional<TaskRun> find(String userId, String runId) {
        return Optional.ofNullable(runMapper.selectByRunId(userId, runId));
    }

    /**
     * 抢占运行执行权：允许从 PENDING/FAILED/PARTIAL_FAILED 重试，
     * 也允许接管超过 staleSeconds 的僵尸 RUNNING。
     *
     * 升级前的旧令牌没有 run 记录：返回 true 继续走旧单步台账逻辑，不阻断兼容链路。
     */
    @Transactional
    public boolean beginRun(String userId, String runId) {
        int staleSeconds = Math.max(1, properties.getTool().getConfirmation().getStaleSeconds());
        if (runMapper.markRunning(userId, runId, staleSeconds) > 0) {
            return true;
        }
        return runMapper.selectByRunId(userId, runId) == null;
    }

    /** 全部步骤成功：记录汇总结果。 */
    @Transactional
    public void markSucceeded(String userId, String runId, String result) {
        runMapper.markSucceeded(userId, runId, result);
    }

    /**
     * 记录失败进度。
     *
     * completedSteps=0 记为 FAILED，否则 PARTIAL_FAILED；current_step=失败步骤序号。
     */
    @Transactional
    public void markFailed(String userId, String runId, int completedSteps, int failedStep,
                           String errorCode, String errorMessage, String result) {
        String status = completedSteps <= 0
                ? TaskRun.Status.FAILED.name() : TaskRun.Status.PARTIAL_FAILED.name();
        runMapper.markFailed(userId, runId, status, failedStep, errorCode, errorMessage, result);
        log.warn("[task-run] failed, runTail={}, status={}, failedStep={}, completed={}",
                tail(runId), status, failedStep, completedSteps);
    }

    /** 用户取消：只取消尚未成功的运行，避免覆盖已执行结果。 */
    @Transactional
    public void markCancelled(String userId, String runId) {
        runMapper.markCancelled(userId, runId);
    }

    /** 新消息放弃旧确认：取消该会话所有仍 PENDING 的运行。 */
    @Transactional
    public int cancelPendingByConversation(String userId, String conversationId) {
        int changed = runMapper.markCancelledByConversation(userId, conversationId);
        if (changed > 0) {
            log.info("[task-run] pending cancelled by new message, conversation={}, count={}",
                    conversationId, changed);
        }
        return changed;
    }

    /** 日志脱敏：只保留运行 ID 末 8 位。 */
    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
