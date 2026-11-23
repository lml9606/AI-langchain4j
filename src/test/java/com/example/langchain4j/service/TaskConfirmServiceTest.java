package com.example.langchain4j.service;

import com.example.langchain4j.audit.AuditEntry;
import com.example.langchain4j.audit.AuditRecorder;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.common.nodes.FinalizeNode;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.graph.task.nodes.TaskExecuteNode;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 确认执行编排单测：顺序、授权入参、状态合并与节点审计。 */
@ExtendWith(MockitoExtension.class)
class TaskConfirmServiceTest {

    @Mock
    private TaskExecuteNode taskExecuteNode;
    @Mock
    private TaskAnswerNode taskAnswerNode;
    @Mock
    private FinalizeNode finalizeNode;
    @Mock
    private AuditRecorder auditRecorder;

    private final AgentProperties properties = new AgentProperties();
    private TaskConfirmService service;

    @BeforeEach
    void setUp() {
        service = new TaskConfirmService(taskExecuteNode, taskAnswerNode, finalizeNode,
                properties, auditRecorder);
    }

    @Test
    @DisplayName("按执行、回答、收尾顺序调用，并合并各节点增量状态")
    void executesNodesInOrderAndMergesState() {
        when(taskExecuteNode.apply(any())).thenReturn(Map.of(
                WorkflowState.TASK_TRACE, List.of(Map.of("tool", "write_tool",
                        "success", true)),
                WorkflowState.TASK_EVIDENCE, List.of(),
                WorkflowState.TASK_RUN_STATUS, "SUCCEEDED",
                WorkflowState.ERROR, ""));
        when(taskAnswerNode.apply(any())).thenReturn(Map.of(
                WorkflowState.RESULT, "已完成",
                WorkflowState.TASK_CONFIDENCE, 0.9,
                WorkflowState.TASK_CONFIDENCE_REASON, "工具成功",
                WorkflowState.ERROR, ""));
        when(finalizeNode.apply(any())).thenReturn(Map.of(
                WorkflowState.RESULT, "已完成",
                WorkflowState.DONE, true));

        WorkflowState result = service.execute(action(), "demo", "确认执行：修改业务数据");

        assertThat(result.result()).isEqualTo("已完成");
        assertThat(result.done()).isTrue();
        assertThat(result.taskRunStatus()).isEqualTo("SUCCEEDED");
        assertThat(result.taskTrace()).hasSize(1);

        InOrder order = inOrder(taskExecuteNode, taskAnswerNode, finalizeNode);
        ArgumentCaptor<WorkflowState> executeState = ArgumentCaptor.forClass(WorkflowState.class);
        order.verify(taskExecuteNode).apply(executeState.capture());
        order.verify(taskAnswerNode).apply(any());
        order.verify(finalizeNode).apply(any());
        assertThat(executeState.getValue().taskApproved()).isTrue();
        assertThat(executeState.getValue().taskIdempotencyKey()).isEqualTo("cfm_1");
        assertThat(executeState.getValue().taskRunId()).isEqualTo("run_1");
        assertThat(executeState.getValue().taskPlan()).hasSize(1);

        verify(auditRecorder, times(3)).record(any(AuditEntry.class));
    }

    private ConfirmationService.PendingAction action() {
        List<Map<String, Object>> plan = List.of(Map.of(
                "stepNo", 0,
                "tool", "write_tool",
                "args", Map.of("recordId", 1),
                "purpose", "修改业务数据"));
        return new ConfirmationService.PendingAction(
                "cfm_1", "demo", "c1", "model-x", "run_1", "write_tool",
                List.of(new ConfirmationService.PendingWriteStep(0, "write_tool",
                        Map.of("recordId", 1))),
                plan, "即将执行：修改业务数据。", "WRITE",
                System.currentTimeMillis() + 60_000);
    }
}
