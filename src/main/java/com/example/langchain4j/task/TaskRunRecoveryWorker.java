package com.example.langchain4j.task;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.service.TaskRunService;
import com.example.langchain4j.tool.runtime.TaskRunExecutor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

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
 * <p>注意：恢复只负责把业务步骤执行完，不会重新生成助手消息；
 * 消息补偿由调用方根据 run 状态查询处理（当前先保证业务事实正确）。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "agent.task.recovery", name = "enabled",
        havingValue = "true", matchIfMissing = true)
public class TaskRunRecoveryWorker {

    private final TaskRunService runService;
    private final TaskRunExecutor runExecutor;
    private final AgentProperties properties;

    public TaskRunRecoveryWorker(TaskRunService runService, TaskRunExecutor runExecutor,
                                 AgentProperties properties) {
        this.runService = runService;
        this.runExecutor = runExecutor;
        this.properties = properties;
    }

    @Scheduled(fixedDelayString = "${agent.task.recovery.interval-ms:30000}")
    public void recover() {
        AgentProperties.Task.Recovery recovery = properties.getTask().getRecovery();
        List<TaskRun> runs = runService.findLeaseExpired(recovery.getBatchSize());
        if (runs.isEmpty()) {
            return;
        }
        log.info("[task-recovery] found {} expired runs", runs.size());
        for (TaskRun run : runs) {
            try {
                runExecutor.execute(run.getUserId(), run.getId(), true);
            } catch (RuntimeException e) {
                // 单条任务恢复失败不能影响其他任务；租约到期后会再次被扫描到
                log.error("[task-recovery] resume failed, runTail={}",
                        idTail(run.getId()), e);
            }
        }
    }

    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
