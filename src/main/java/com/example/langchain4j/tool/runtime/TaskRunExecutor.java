package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.repository.entity.TaskStep;
import com.example.langchain4j.service.TaskRunService;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.workflow.TaskWorkflow;
import com.example.langchain4j.tool.workflow.TaskWorkflowRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * 任务执行器：按步骤表推进一条 {@link TaskRun}。
 *
 * 已成功步骤直接跳过，失败重试从失败步骤继续；未确认时只执行只读步骤，遇到写步骤立即
 * 停下等待确认。写步骤必须 approved=true，并使用 runId:stepNo 作为幂等键；同一阶段内标记
 * parallel 的只读步骤可以并行，写步骤永远串行；某步失败即停止，后续步骤标记 SKIPPED，
 * 任务状态 FAILED。
 *
 * 执行租约保证多实例下同一条任务只有一个执行者；进程崩溃后租约过期，
 * 恢复 worker 可以接管并从 currentStep 继续。
 */
@Slf4j
@Component
public class TaskRunExecutor {

    private final TaskRunService runService;
    private final ToolExecutor toolExecutor;
    private final TaskArgsResolver argsResolver;
    private final TaskWorkflowRegistry workflowRegistry;
    private final AgentProperties properties;
    private final ThreadPoolTaskExecutor stepPool;

    /**
     * 生产构造：stepPool 用于同阶段只读步骤并行。
     */
    @Autowired
    public TaskRunExecutor(TaskRunService runService,
                           ToolExecutor toolExecutor,
                           TaskArgsResolver argsResolver,
                           TaskWorkflowRegistry workflowRegistry,
                           AgentProperties properties,
                           @Qualifier("taskStepPool") ThreadPoolTaskExecutor stepPool) {
        this.runService = runService;
        this.toolExecutor = toolExecutor;
        this.argsResolver = argsResolver;
        this.workflowRegistry = workflowRegistry;
        this.properties = properties;
        this.stepPool = stepPool;
    }

    /** 串行装配：不注入步骤线程池，同阶段只读步骤按顺序执行；生产链路使用完整构造。 */
    public TaskRunExecutor(TaskRunService runService,
                           ToolExecutor toolExecutor,
                           TaskArgsResolver argsResolver,
                           TaskWorkflowRegistry workflowRegistry,
                           AgentProperties properties) {
        this(runService, toolExecutor, argsResolver, workflowRegistry, properties, null);
    }

    /**
     * 推进一条任务。
     *
     * 返回任务当前快照；任务不存在时抛 IllegalArgumentException，执行异常会先把任务标记
     * FAILED 再向上抛出。写步骤的幂等、业务事务和租约释放分别由 ToolExecutor、台账服务
     * 和 finally 处理。
     *
     * @param userId  任务归属（数据隔离）
     * @param runId   任务 ID
     * @param approved 是否已获得用户确认；false 时遇到写步骤会停下
     * @return 本次推进后的任务状态与步骤快照
     */
    public TaskRunResult execute(String userId, String runId, boolean approved) {
        TaskRun run = runService.find(userId, runId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + runId));
        if (run.terminal()) {
            return assemble(run, runService.steps(runId));
        }

        String owner = "exec_" + UUID.randomUUID().toString().replace("-", "");
        int leaseSeconds = Math.max(5, properties.getTask().getLeaseSeconds());
        if (!runService.tryAcquireLease(runId, owner, leaseSeconds)) {
            log.info("[task-exec] busy, runTail={}, owner={}", idTail(runId), owner);
            return assembleBusy(run, runService.steps(runId));
        }

        try {
            if (approved) {
                runService.markRunning(userId, runId, run.getConfirmationId());
                run = runService.find(userId, runId).orElse(run);
                // 上一轮某步失败后，后续步骤被标记 SKIPPED；重试时必须恢复为 PENDING，
                // 否则失败步骤成功后会直接跳过全部后续步骤，任务被错误地标记为成功。
                runService.resetSkipped(runId);
            }
            List<TaskStep> steps = runService.steps(runId);
            Map<Integer, TaskStep> byNo = index(steps);
            log.info("[task-exec] start, runTail={}, steps={}, approved={}, status={}",
                    idTail(runId), steps.size(), approved, run.getStatus());

            int index = 0;
            boolean waitingWrites = false;
            while (index < steps.size()) {
                TaskStep step = steps.get(index);
                if (isTerminal(step.getStatus())) {
                    index++;
                    continue;
                }
                // 长任务可能超过一个租约周期：每推进一步前续租，续租失败立即停止。
                if (!runService.renewLease(runId, owner, leaseSeconds)) {
                    log.warn("[task-exec] lease lost, stop without writing, runTail={}, step={}",
                            idTail(runId), step.getStepNo());
                    TaskRun latest = runService.find(userId, runId).orElse(run);
                    return assembleBusy(latest, runService.steps(runId));
                }
                // 未确认：只读步骤可以继续跑，遇到写步骤立即停下等确认
                if (step.write() && !approved) {
                    waitingWrites = true;
                    break;
                }

                boolean parallel = canRunParallel(run, steps, index);
                if (parallel) {
                    int end = parallelGroupEnd(steps, index);
                    List<TaskStep> group = new ArrayList<>(steps.subList(index, end));
                    List<StepOutcome> outcomes = executeGroup(userId, run, group, byNo, approved);
                    boolean failed = persistGroup(userId, run, outcomes, end);
                    if (failed) {
                        return failed(run, userId, runId, outcomes);
                    }
                    index = end;
                    runService.advance(userId, runId, index);
                    continue;
                }

                StepOutcome outcome = executeOne(userId, run, step, byNo, approved);
                persist(userId, run, outcome);
                if (!outcome.result().success()) {
                    return failed(run, userId, runId, List.of(outcome));
                }
                index++;
                runService.advance(userId, runId, index);
            }

            if (waitingWrites) {
                log.info("[task-exec] paused before writes, runTail={}, currentStep={}",
                        idTail(runId), index);
                return assemble(run, runService.steps(runId));
            }

            runService.finish(userId, runId, TaskRun.Status.SUCCEEDED, steps.size(), "", "");
            TaskRun finished = runService.find(userId, runId).orElse(run);
            log.info("[task-exec] succeeded, runTail={}, steps={}", idTail(runId), steps.size());
            return assemble(finished, runService.steps(runId));
        } catch (RuntimeException e) {
            log.error("[task-exec] unexpected failure, runTail={}", idTail(runId), e);
            runService.finish(userId, runId, TaskRun.Status.FAILED,
                    run.getCurrentStep(), "EXECUTION_ERROR", e.getMessage());
            throw e;
        } finally {
            runService.releaseLease(runId, owner);
        }
    }

    /** 读取任务当前状态，不触发执行；供状态查询接口使用。 */
    public TaskRunResult describe(String userId, String runId) {
        TaskRun run = runService.find(userId, runId)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在: " + runId));
        return assemble(run, runService.steps(runId));
    }

    private TaskRunResult failed(TaskRun run, String userId, String runId,
                                 List<StepOutcome> outcomes) {
        StepOutcome failed = outcomes.stream()
                .filter(item -> !item.result().success())
                .findFirst().orElse(null);
        int failedStep = failed == null ? run.getCurrentStep() : failed.step().getStepNo();
        String code = failed == null ? "STEP_FAILED" : failed.result().errorCode();
        String message = failed == null ? "任务步骤执行失败"
                : failed.result().errorMessage();
        runService.markRemainingSkipped(runId, failedStep + 1);
        runService.finish(userId, runId, TaskRun.Status.FAILED, failedStep, code, message);
        TaskRun current = runService.find(userId, runId).orElse(run);
        log.warn("[task-exec] failed, runTail={}, step={}, code={}, reason={}",
                idTail(runId), failedStep, code, message);
        return assemble(current, runService.steps(runId));
    }

    private List<StepOutcome> executeGroup(String userId, TaskRun run, List<TaskStep> group,
                                           Map<Integer, TaskStep> byNo, boolean approved) {
        if (stepPool == null) {
            List<StepOutcome> outcomes = new ArrayList<>(group.size());
            for (TaskStep step : group) {
                outcomes.add(executeOne(userId, run, step, byNo, approved));
            }
            return outcomes;
        }
        List<CompletableFuture<StepOutcome>> futures = new ArrayList<>(group.size());
        for (TaskStep step : group) {
            futures.add(CompletableFuture.supplyAsync(
                    () -> executeOne(userId, run, step, byNo, approved), stepPool));
        }
        List<StepOutcome> outcomes = new ArrayList<>(group.size());
        for (CompletableFuture<StepOutcome> future : futures) {
            outcomes.add(future.join());
        }
        return outcomes;
    }

    private StepOutcome executeOne(String userId, TaskRun run, TaskStep step,
                                   Map<Integer, TaskStep> byNo, boolean approved) {
        runService.markStepRunning(run.getId(), step.getStepNo());
        JsonNode originalArgs = parseArgs(step.getArgsJson());
        JsonNode resolvedArgs;
        try {
            resolvedArgs = argsResolver.resolve(originalArgs, byNo);
        } catch (TaskArgsResolver.TaskRefException e) {
            return new StepOutcome(step, originalArgs,
                    ToolResult.failure("REF_UNRESOLVED", e.getMessage(), 0));
        }

        String argsJson = JsonUtil.toJson(resolvedArgs);
        runService.updateStepArgs(run.getId(), step.getStepNo(), argsJson);
        step.setArgsJson(argsJson);
        boolean write = step.write();
        ToolContext context = new ToolContext(userId, run.getConversationId(),
                write && approved, write ? idempotencyKey(run, step) : "");
        ToolResult result = toolExecutor.invoke(context, step.getToolName(), resolvedArgs);
        log.info("[task-exec] step done, runTail={}, step={}, tool={}, write={}, success={}",
                idTail(run.getId()), step.getStepNo(), step.getToolName(), write, result.success());
        return new StepOutcome(step, resolvedArgs, result);
    }

    private boolean persistGroup(String userId, TaskRun run, List<StepOutcome> outcomes,
                                 int nextStep) {
        boolean failed = false;
        for (StepOutcome outcome : outcomes) {
            persist(userId, run, outcome);
            if (!outcome.result().success()) {
                failed = true;
            }
        }
        if (!failed) {
            runService.advance(userId, run.getId(), nextStep);
        }
        return failed;
    }

    private void persist(String userId, TaskRun run, StepOutcome outcome) {
        ToolResult result = outcome.result();
        if (result.success()) {
            String summary = Texts.truncate(result.summary(),
                    properties.getLimits().getTaskSummaryMaxLength());
            String resultJson = JsonUtil.toJson(result.data());
            runService.markStepSucceeded(run.getId(), outcome.step().getStepNo(), summary,
                    resultJson, JsonUtil.toJson(evidence(result)),
                    result.costMs());
            outcome.step().setStatus(TaskStep.Status.SUCCEEDED);
            outcome.step().setResultSummary(summary);
            outcome.step().setResultJson(resultJson);
            outcome.step().setCostMs(result.costMs());
        } else {
            runService.markStepFailed(run.getId(), outcome.step().getStepNo(),
                    result.errorCode(), result.errorMessage(), result.costMs());
            outcome.step().setStatus(TaskStep.Status.FAILED);
            outcome.step().setErrorCode(result.errorCode());
            outcome.step().setErrorMessage(result.errorMessage());
            outcome.step().setCostMs(result.costMs());
        }
    }

    private List<Map<String, Object>> evidence(ToolResult result) {
        List<Map<String, Object>> items = new ArrayList<>();
        int max = properties.getLimits().getTaskEvidenceMaxEntries();
        for (ToolEvidence item : result.evidence()) {
            if (items.size() >= max) {
                break;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("type", item.type());
            map.put("sourceId", item.sourceId());
            map.put("title", item.title());
            map.put("snippet", Texts.truncate(item.snippet(),
                    properties.getLimits().getTaskSummaryMaxLength()));
            map.put("score", item.score());
            items.add(map);
        }
        return items;
    }

    private boolean canRunParallel(TaskRun run, List<TaskStep> steps, int index) {
        if (!properties.getTask().isParallelReads() || stepPool == null) {
            return false;
        }
        TaskStep step = steps.get(index);
        if (step.write()) {
            return false;
        }
        String stageId = step.getStageId();
        if (stageId == null || stageId.isBlank()) {
            return false;
        }
        return parallelGroupEnd(steps, index) > index + 1 && stageParallel(run, stageId);
    }

    private int parallelGroupEnd(List<TaskStep> steps, int start) {
        String stageId = steps.get(start).getStageId();
        int end = start;
        while (end < steps.size()) {
            TaskStep step = steps.get(end);
            if (step.write() || isTerminal(step.getStatus())
                    || !stageId.equals(step.getStageId())) {
                break;
            }
            end++;
        }
        return end;
    }

    private boolean stageParallel(TaskRun run, String stageId) {
        return workflowRegistry.find(run.getWorkflowId())
                .flatMap(workflow -> workflow.stages().stream()
                        .filter(stage -> stage.id().equals(stageId))
                        .findFirst())
                .map(TaskWorkflow.Stage::parallel)
                .orElse(false);
    }

    private String idempotencyKey(TaskRun run, TaskStep step) {
        // 用 runId 而不是 confirmationId：用户重新确认会生成新令牌，
        // 但同一条任务同一步骤的幂等键必须保持不变，否则已成功的写会被重复执行。
        String base = run.getId() == null || run.getId().isBlank()
                ? run.getConfirmationId() : run.getId();
        return base + ":" + step.getStepNo();
    }

    private JsonNode parseArgs(String argsJson) {
        if (argsJson == null || argsJson.isBlank()) {
            return JsonUtil.mapper().createObjectNode();
        }
        try {
            return JsonUtil.mapper().readTree(argsJson);
        } catch (Exception e) {
            return JsonUtil.mapper().createObjectNode();
        }
    }

    private Map<Integer, TaskStep> index(List<TaskStep> steps) {
        Map<Integer, TaskStep> result = new LinkedHashMap<>();
        for (TaskStep step : steps) {
            result.put(step.getStepNo(), step);
        }
        return result;
    }

    private boolean isTerminal(TaskStep.Status status) {
        return status == TaskStep.Status.SUCCEEDED
                || status == TaskStep.Status.SKIPPED
                || status == TaskStep.Status.CANCELLED;
    }

    private TaskRunResult assemble(TaskRun run, List<TaskStep> steps) {
        List<Map<String, Object>> trace = new ArrayList<>(steps.size());
        List<Map<String, Object>> evidence = new ArrayList<>();
        int maxEvidence = properties.getLimits().getTaskEvidenceMaxEntries();
        for (TaskStep step : steps) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("tool", step.getToolName());
            item.put("args", parseArgs(step.getArgsJson()));
            item.put("purpose", step.getPurpose());
            item.put("success", step.getStatus() == TaskStep.Status.SUCCEEDED);
            item.put("summary", step.getResultSummary());
            item.put("errorCode", step.getErrorCode());
            item.put("costMs", step.getCostMs() == null ? 0L : step.getCostMs());
            trace.add(item);

            if (evidence.size() < maxEvidence && step.getEvidenceJson() != null
                    && !step.getEvidenceJson().isBlank()) {
                List<Map<String, Object>> stored = JsonUtil.fromJson(step.getEvidenceJson(),
                        new TypeReference<List<Map<String, Object>>>() {
                        });
                if (stored != null) {
                    for (Map<String, Object> row : stored) {
                        if (evidence.size() >= maxEvidence) {
                            break;
                        }
                        evidence.add(row);
                    }
                }
            }
        }
        return new TaskRunResult(run, steps, trace, evidence, false);
    }

    /** 另一个实例正在执行：不返回半成品轨迹，调用方应提示用户查询进度而不是继续回答。 */
    private TaskRunResult assembleBusy(TaskRun run, List<TaskStep> steps) {
        TaskRunResult snapshot = assemble(run, steps);
        return new TaskRunResult(snapshot.run(), snapshot.steps(), snapshot.trace(),
                snapshot.evidence(), true);
    }

    private record StepOutcome(TaskStep step, JsonNode args, ToolResult result) {
    }

    /** 执行结果：run 快照 + 步骤快照 + 回答节点需要的轨迹/证据。 */
    public record TaskRunResult(TaskRun run, List<TaskStep> steps,
                                List<Map<String, Object>> trace,
                                List<Map<String, Object>> evidence,
                                boolean busy) {

        public boolean succeeded() {
            return run.getStatus() == TaskRun.Status.SUCCEEDED;
        }

        public boolean failed() {
            return run.getStatus() == TaskRun.Status.FAILED;
        }
    }

    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
