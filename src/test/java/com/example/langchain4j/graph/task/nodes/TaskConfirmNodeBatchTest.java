package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.repository.entity.TaskStep;
import com.example.langchain4j.service.TaskRunService;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ConfirmationPolicy;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import com.example.langchain4j.tool.runtime.TaskArgsResolver;
import com.example.langchain4j.tool.workflow.TaskPlanValidator;
import com.example.langchain4j.tool.workflow.ValidationResult;
import org.junit.jupiter.api.AfterEach;
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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 批量确认节点：写步骤参数使用只读步骤的真实结果，并绑定到同一个 run。 */
@ExtendWith(MockitoExtension.class)
class TaskConfirmNodeBatchTest {

    @Mock
    private ConfirmationService confirmationService;
    @Mock
    private ConfirmationPolicy confirmationPolicy;
    @Mock
    private TaskPlanValidator planValidator;
    @Mock
    private TaskRunService taskRunService;

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    @Test
    @DisplayName("批量确认 -> 写参数解析为真实 recordId，并携带 runId")
    void createsBatchConfirmationWithResolvedArgs() {
        UserContext.set("demo", "USER");
        TaskConfirmNode node = new TaskConfirmNode(confirmationService, confirmationPolicy,
                planValidator, taskRunService, new TaskArgsResolver());
        TaskRun run = new TaskRun();
        run.setId("run_1");
        run.setUserId("demo");
        run.setConversationId("c1");
        run.setStatus(TaskRun.Status.PLANNING);
        TaskStep read = step(0, "query_demo_record", "READ_ONLY", TaskStep.Status.SUCCEEDED);
        read.setResultJson("[{\"id\":7}]");
        TaskStep write = step(1, "complete_demo_record", "WRITE", TaskStep.Status.PENDING);
        write.setArgsJson("{\"recordId\":{\"$ref\":\"0.0.id\"}}");
        when(taskRunService.find("demo", "run_1")).thenReturn(Optional.of(run));
        when(taskRunService.steps("run_1")).thenReturn(List.of(read, write));
        when(taskRunService.markWaitingConfirm(eq("demo"), eq("run_1"), eq("cfm_1"), any()))
                .thenReturn(true);
        when(confirmationPolicy.check(eq("complete_demo_record"), anyString()))
                .thenReturn(new ConfirmationPolicy.Decision(true, definition(),
                        ConfirmationPolicy.Status.ALLOWED, ""));
        when(planValidator.validateConfirm(anyList(), any()))
                .thenReturn(new TaskPlanValidator.PlanValidationResult(
                        ValidationResult.ok(), List.of(), definition()));
        when(confirmationService.create(anyString(), anyString(), anyString(), eq("run_1"),
                anyList(), anyString(), anyString(), anyList()))
                .thenAnswer(invocation -> new ConfirmationService.PendingAction(
                        "cfm_1", "demo", "c1", "model", "run_1", "complete_demo_record",
                        invocation.getArgument(7), invocation.getArgument(4),
                        "即将执行", "WRITE", System.currentTimeMillis() + 60_000));

        Map<String, Object> updates = node.apply(state());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ConfirmationService.PendingWriteStep>> steps =
                ArgumentCaptor.forClass(List.class);
        org.mockito.Mockito.verify(confirmationService).create(anyString(), anyString(),
                anyString(), eq("run_1"), anyList(), anyString(), anyString(), steps.capture());
        assertThat(steps.getValue()).hasSize(1);
        assertThat(steps.getValue().get(0).args().get("recordId")).isEqualTo(7);
        assertThat(updates.get(WorkflowState.TASK_RUN_STATUS))
                .isEqualTo(TaskRun.Status.WAITING_CONFIRM.name());
        assertThat(updates.get(WorkflowState.ERROR)).isEqualTo("");
        assertThat(JsonUtil.toJson(updates.get(WorkflowState.TASK_PENDING_ACTION)))
                .contains("cfm_1").contains("run_1");
    }

    private WorkflowState state() {
        Map<String, Object> read = Map.of("stepNo", 0, "workflowId", "wf",
                "stage", "query", "tool", "query_demo_record", "args", Map.of(),
                "purpose", "查询", "sideEffect", "READ_ONLY");
        Map<String, Object> write = Map.of("stepNo", 1, "workflowId", "wf",
                "stage", "complete", "tool", "complete_demo_record",
                "args", Map.of("recordId", 0), "purpose", "完成", "sideEffect", "WRITE");
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, "完成记录",
                WorkflowState.MODEL, "model",
                WorkflowState.USER_ID, "demo",
                WorkflowState.CONVERSATION_ID, "c1",
                WorkflowState.TASK_RUN_ID, "run_1",
                WorkflowState.TASK_WORKFLOW_ID, "wf",
                WorkflowState.TASK_PLAN, List.of(read, write)));
    }

    private TaskStep step(int no, String tool, String sideEffect, TaskStep.Status status) {
        TaskStep step = new TaskStep();
        step.setRunId("run_1");
        step.setStepNo(no);
        step.setToolName(tool);
        step.setSideEffect(sideEffect);
        step.setStatus(status);
        step.setArgsJson("{}");
        return step;
    }

    private ToolDefinition definition() {
        return new ToolDefinition("complete_demo_record", "完成记录",
                "{\"type\":\"object\",\"properties\":{\"recordId\":{\"type\":\"integer\"}}}",
                ToolSideEffect.WRITE, 3000);
    }
}
