package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.repository.entity.TaskStep;
import com.example.langchain4j.service.TaskRunService;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.workflow.TaskWorkflowRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 多步骤执行器：只读先跑、写步骤等待确认、逐步幂等、失败即停。 */
@ExtendWith(MockitoExtension.class)
class TaskRunExecutorTest {

    @Mock
    private TaskRunService runService;
    @Mock
    private ToolExecutor toolExecutor;
    @Mock
    private TaskWorkflowRegistry workflowRegistry;

    private AgentProperties properties;
    private TaskRunExecutor executor;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getTask().setParallelReads(false);
        executor = new TaskRunExecutor(runService, toolExecutor, new TaskArgsResolver(),
                workflowRegistry, properties);
        lenient().when(runService.tryAcquireLease(eq("run_1"), anyString(), anyInt()))
                .thenReturn(true);
        lenient().when(runService.renewLease(eq("run_1"), anyString(), anyInt()))
                .thenReturn(true);
        lenient().when(runService.markStepRunning(eq("run_1"), anyInt())).thenReturn(true);
    }

    @Test
    @DisplayName("未确认 -> 执行只读步骤，遇到写步骤停下")
    void executesReadsOnlyWhenNotApproved() {
        TaskRun run = run(TaskRun.Status.PLANNING, null);
        List<TaskStep> steps = List.of(read(0, TaskStep.Status.PENDING), write(1, TaskStep.Status.PENDING, "{}"));
        stub(run, steps);
        when(toolExecutor.invoke(any(), eq("read_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.success(List.of(Map.of("id", 7)), "ok", List.of(), 1));

        TaskRunExecutor.TaskRunResult result = executor.execute("demo", "run_1", false);

        verify(toolExecutor).invoke(any(), eq("read_tool"), any(JsonNode.class));
        verify(toolExecutor, never()).invoke(any(), eq("write_tool"), any(JsonNode.class));
        verify(runService, never()).finish(anyString(), anyString(), eq(TaskRun.Status.SUCCEEDED),
                anyInt(), anyString(), anyString());
        assertThat(result.run().getStatus()).isEqualTo(TaskRun.Status.PLANNING);
    }

    @Test
    @DisplayName("已确认 -> 跳过已成功只读步骤，写步骤使用 stepNo 幂等键")
    void executesWriteWithPerStepIdempotencyKey() {
        TaskRun run = run(TaskRun.Status.RUNNING, "cfm_1");
        TaskStep read = read(0, TaskStep.Status.SUCCEEDED);
        read.setResultJson("[{\"id\":7}]");
        TaskStep write = write(1, TaskStep.Status.PENDING, "{\"recordId\":{\"$ref\":\"0.0.id\"}}");
        stub(run, List.of(read, write));
        when(toolExecutor.invoke(any(), eq("write_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.success(Map.of("recordId", 7), "done", List.of(), 2));

        executor.execute("demo", "run_1", true);

        ArgumentCaptor<ToolContext> context = ArgumentCaptor.forClass(ToolContext.class);
        ArgumentCaptor<JsonNode> args = ArgumentCaptor.forClass(JsonNode.class);
        verify(toolExecutor).invoke(context.capture(), eq("write_tool"), args.capture());
        assertThat(context.getValue().approved()).isTrue();
        assertThat(context.getValue().idempotencyKey()).isEqualTo("run_1:1");
        assertThat(args.getValue().get("recordId").asInt()).isEqualTo(7);
        verify(runService).finish("demo", "run_1", TaskRun.Status.SUCCEEDED, 2, "", "");
    }

    @Test
    @DisplayName("写步骤失败 -> 标记失败并把后续步骤跳过")
    void writeFailureSkipsRemainingSteps() {
        TaskRun run = run(TaskRun.Status.RUNNING, "cfm_1");
        TaskStep readStep = read(0, TaskStep.Status.SUCCEEDED);
        readStep.setResultJson("[]");
        TaskStep writeStep = write(1, TaskStep.Status.PENDING, "{}");
        TaskStep next = write(2, TaskStep.Status.PENDING, "{}");
        stub(run, List.of(readStep, writeStep, next));
        when(toolExecutor.invoke(any(), eq("write_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.failure("BIZ_ERROR", "业务拒绝", 1));

        TaskRunExecutor.TaskRunResult result = executor.execute("demo", "run_1", true);

        verify(runService).markStepFailed("run_1", 1, "BIZ_ERROR", "业务拒绝", 1);
        verify(runService).markRemainingSkipped("run_1", 2);
        verify(runService).finish("demo", "run_1", TaskRun.Status.FAILED, 1,
                "BIZ_ERROR", "业务拒绝");
        assertThat(result.run().getStatus()).isEqualTo(TaskRun.Status.FAILED);
    }

    @Test
    @DisplayName("执行租约被占用 -> 返回 busy，不执行工具、不产出半成品轨迹")
    void leaseConflictReturnsBusy() {
        TaskRun run = run(TaskRun.Status.RUNNING, "cfm_1");
        List<TaskStep> steps = List.of(read(0, TaskStep.Status.SUCCEEDED),
                write(1, TaskStep.Status.PENDING, "{}"));
        stub(run, steps);
        when(runService.tryAcquireLease(eq("run_1"), anyString(), anyInt())).thenReturn(false);

        TaskRunExecutor.TaskRunResult result = executor.execute("demo", "run_1", true);

        assertThat(result.busy()).isTrue();
        assertThat(result.run().getStatus()).isEqualTo(TaskRun.Status.RUNNING);
        verify(toolExecutor, never()).invoke(any(), anyString(), any(JsonNode.class));
        verify(runService, never()).markRunning(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("续租失败 -> 立即停止，不继续执行下一步")
    void lostLeaseStopsExecution() {
        TaskRun run = run(TaskRun.Status.RUNNING, "cfm_1");
        List<TaskStep> steps = List.of(read(0, TaskStep.Status.PENDING));
        stub(run, steps);
        when(runService.renewLease(eq("run_1"), anyString(), anyInt())).thenReturn(false);

        TaskRunExecutor.TaskRunResult result = executor.execute("demo", "run_1", true);

        assertThat(result.busy()).isTrue();
        verify(toolExecutor, never()).invoke(any(), anyString(), any(JsonNode.class));
    }

    @Test
    @DisplayName("失败重试 -> 恢复上一轮被跳过的步骤，继续执行剩余写步骤")
    void retryResetsSkippedSteps() {
        TaskRun run = run(TaskRun.Status.FAILED, "cfm_1");
        TaskStep read = read(0, TaskStep.Status.SUCCEEDED);
        read.setResultJson("[]");
        TaskStep failedWrite = write(1, TaskStep.Status.FAILED, "{}");
        TaskStep skippedWrite = write(2, TaskStep.Status.SKIPPED, "{}");
        List<TaskStep> steps = List.of(read, failedWrite, skippedWrite);
        stub(run, steps);
        doAnswer(invocation -> {
            skippedWrite.setStatus(TaskStep.Status.PENDING);
            return null;
        }).when(runService).resetSkipped("run_1");
        when(toolExecutor.invoke(any(), eq("write_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.success(Map.of("ok", true), "done", List.of(), 1));

        TaskRunExecutor.TaskRunResult result = executor.execute("demo", "run_1", true);

        verify(runService).resetSkipped("run_1");
        verify(toolExecutor, times(2)).invoke(any(), eq("write_tool"), any(JsonNode.class));
        verify(runService).finish("demo", "run_1", TaskRun.Status.SUCCEEDED, 3, "", "");
        assertThat(result.run().getStatus()).isEqualTo(TaskRun.Status.SUCCEEDED);
    }

    private TaskRun run(TaskRun.Status status, String confirmationId) {
        TaskRun run = new TaskRun();
        run.setId("run_1");
        run.setConversationId("c1");
        run.setUserId("demo");
        run.setWorkflowId("wf");
        run.setStatus(status);
        run.setCurrentStep(0);
        run.setConfirmationId(confirmationId);
        return run;
    }

    private TaskStep read(int no, TaskStep.Status status) {
        TaskStep step = new TaskStep();
        step.setRunId("run_1");
        step.setStepNo(no);
        step.setToolName("read_tool");
        step.setSideEffect("READ_ONLY");
        step.setStatus(status);
        step.setArgsJson("{}");
        return step;
    }

    private TaskStep write(int no, TaskStep.Status status, String argsJson) {
        TaskStep step = new TaskStep();
        step.setRunId("run_1");
        step.setStepNo(no);
        step.setToolName("write_tool");
        step.setSideEffect("WRITE");
        step.setStatus(status);
        step.setArgsJson(argsJson);
        return step;
    }

    private void stub(TaskRun run, List<TaskStep> steps) {
        when(runService.find("demo", "run_1")).thenReturn(Optional.of(run));
        when(runService.steps("run_1")).thenReturn(steps);
        lenient().when(runService.markRunning("demo", "run_1", run.getConfirmationId()))
                .thenReturn(true);
        lenient().doAnswer(invocation -> {
            run.setStatus(invocation.getArgument(2));
            return null;
        }).when(runService).finish(anyString(), anyString(), any(TaskRun.Status.class),
                anyInt(), anyString(), anyString());
    }
}
