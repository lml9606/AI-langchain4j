package com.example.langchain4j.tool.confirmation;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.graph.common.nodes.FinalizeNode;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.graph.task.nodes.TaskWriteNode;
import com.example.langchain4j.tool.run.TaskRunService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 确认执行编排测评：验证固定计划、运行互斥和成功/部分失败台账。 */
@ExtendWith(MockitoExtension.class)
class TaskConfirmServiceTest {

    @Mock
    private TaskWriteNode taskWriteNode;
    @Mock
    private TaskAnswerNode taskAnswerNode;
    @Mock
    private FinalizeNode finalizeNode;
    @Mock
    private TaskRunService taskRunService;

    private TaskConfirmService service;

    /** 构造只依赖节点和台账的确认编排器。 */
    @BeforeEach
    void setUp() {
        service = new TaskConfirmService(
                taskWriteNode, taskAnswerNode, finalizeNode, taskRunService);
    }

    /** 确认执行只使用令牌中的固定计划，不重新规划，并在成功后标记运行成功。 */
    @Test
    void confirmedPlanExecutesAndMarksSucceeded() throws Exception {
        ConfirmationService.PendingAction action = action();
        when(taskRunService.beginRun("alice", action.confirmationId())).thenReturn(true);
        when(taskWriteNode.apply(any())).thenReturn(Map.of(
                WorkflowState.ERROR, "",
                WorkflowState.TASK_TRACE, List.of()));
        when(taskAnswerNode.apply(any())).thenReturn(Map.of(
                WorkflowState.RESULT, "已完成"));
        when(finalizeNode.apply(any())).thenReturn(Map.of(WorkflowState.DONE, true));

        WorkflowState result = service.execute(action, "alice", "确认执行");

        assertThat(result.result()).isEqualTo("已完成");
        ArgumentCaptor<WorkflowState> stateCaptor = ArgumentCaptor.forClass(WorkflowState.class);
        verify(taskWriteNode).apply(stateCaptor.capture());
        assertThat(stateCaptor.getValue().taskApproved()).isTrue();
        assertThat(stateCaptor.getValue().taskIdempotencyKey())
                .isEqualTo(action.confirmationId());
        assertThat(stateCaptor.getValue().taskPlan()).hasSize(2);
        verify(taskRunService).markSucceeded("alice", action.confirmationId(), "已完成");
    }

    /** 同一次确认已在执行时拒绝再次进入节点，避免并发重复写。 */
    @Test
    void concurrentConfirmationIsRejectedBeforeNodes() {
        ConfirmationService.PendingAction action = action();
        when(taskRunService.beginRun("alice", action.confirmationId())).thenReturn(false);

        assertThatThrownBy(() -> service.execute(action, "alice", "确认执行"))
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getCode())
                .isEqualTo(ErrorCode.CONFIRMATION_IN_PROGRESS.getCode());
        verify(taskWriteNode, never()).apply(any());
    }

    /** 第二步失败时记录第一步成功、当前失败步骤和错误码为 PARTIAL_FAILED。 */
    @Test
    void partialFailureRecordsCompletedAndFailedSteps() throws Exception {
        ConfirmationService.PendingAction action = action();
        when(taskRunService.beginRun("alice", action.confirmationId())).thenReturn(true);
        when(taskWriteNode.apply(any())).thenReturn(Map.of(
                WorkflowState.ERROR, "第二步失败",
                WorkflowState.TASK_TRACE, List.of(
                        Map.of("stepNo", 1, "success", true),
                        Map.of("stepNo", 2, "success", false,
                                "errorCode", "BIZ_FAILED", "summary", "record locked"))));
        when(taskAnswerNode.apply(any())).thenReturn(Map.of(
                WorkflowState.RESULT, "部分完成"));
        when(finalizeNode.apply(any())).thenReturn(Map.of(WorkflowState.DONE, true));

        service.execute(action, "alice", "确认执行");

        verify(taskRunService).markFailed(
                eq("alice"), eq(action.confirmationId()), eq(1), eq(2),
                eq("BIZ_FAILED"), eq("record locked"), eq("部分完成"));
        verify(taskRunService, never()).markSucceeded(anyString(), anyString(), anyString());
    }

    /** 构造包含两个固定写步骤的确认令牌。 */
    private ConfirmationService.PendingAction action() {
        return new ConfirmationService.PendingAction(
                "cfm_1", "alice", "c1", "model-x",
                "complete_demo_record", Map.of("recordId", "R1"),
                "完成两条记录", "WRITE", System.currentTimeMillis() + 60_000,
                List.of(
                        new ConfirmationService.PendingAction.Step(
                                1, "complete_demo_record", Map.of("recordId", "R1"), "完成 R1"),
                        new ConfirmationService.PendingAction.Step(
                                2, "complete_demo_record", Map.of("recordId", "R2"), "完成 R2")));
    }
}
