package com.example.langchain4j.service;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.ChatRequest;
import com.example.langchain4j.dto.request.ConfirmRequest;
import com.example.langchain4j.dto.response.ChatResponse;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.guard.SessionLock;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.repository.entity.ConversationSummary;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.repository.entity.TaskStep;
import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.service.model.RewriteResult;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 确认接口服务层单测：会话单 key、令牌消费、拒绝不执行、确认执行不重新规划、业务结果判定。
 */
@ExtendWith(MockitoExtension.class)
class AgentServiceConfirmTest {

    @Mock
    private CompiledGraph<WorkflowState> agentGraph;
    @Mock
    private CompiledGraph<WorkflowState> taskConfirmGraph;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private SessionLock sessionLock;
    @Mock
    private ConversationService conversationService;
    @Mock
    private QueryRewriter queryRewriter;
    @Mock
    private SegmentService segmentService;
    @Mock
    private AuditService auditService;
    @Mock
    private SummaryService summaryService;
    @Mock
    private ModelRegistry modelRegistry;
    @Mock
    private TicketService ticketService;
    @Mock
    private ThreadPoolTaskExecutor agentExecutor;
    private final AgentProperties properties = new AgentProperties();
    @Mock
    private AgentMetrics metrics;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private ConfirmationService confirmationService;
    @Mock
    private ToolExecutionService toolExecutionService;
    @Mock
    private TaskRunService taskRunService;

    private AgentService service;

    @BeforeEach
    void setUp() {
        service = new AgentService(agentGraph, taskConfirmGraph, rateLimiter, sessionLock,
                conversationService, queryRewriter, segmentService, auditService, summaryService,
                modelRegistry, ticketService, agentExecutor, properties, metrics,
                promptTemplateService, confirmationService, toolExecutionService, taskRunService);
        passThroughExecutor();
        passThroughLock();
        UserContext.set("demo", "USER");
        lenient().when(confirmationService.preview("c1", "cfm_1", "demo"))
                .thenReturn(Optional.of(action()));
        lenient().when(taskConfirmGraph.invoke(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(completedState("业务对象 B1001 已取消")));
        // 确认链路以台账判定成败：默认模拟写工具已成功落账
        lenient().when(toolExecutionService.find("demo", "cfm_1"))
                .thenReturn(Optional.of(execution(ToolExecution.Status.SUCCEEDED, null, null)));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ConfirmRequest request(boolean approved) {
        ConfirmRequest request = new ConfirmRequest();
        request.setConversationId("c1");
        request.setConfirmationId("cfm_1");
        request.setApproved(approved);
        return request;
    }

    private ChatRequest chatRequest() {
        ChatRequest request = new ChatRequest();
        request.setQuery("继续");
        request.setConversationId("c1");
        return request;
    }

    private ConfirmationService.PendingAction action() {
        List<Map<String, Object>> plan = List.of(Map.of(
                "tool", "write_tool",
                "args", Map.of("entityId", "B1001", "reason", "不想要了"),
                "purpose", "修改业务数据"));
        return new ConfirmationService.PendingAction(
                "cfm_1", "demo", "c1", "model-x", "",
                "write_tool",
                List.of(new ConfirmationService.PendingWriteStep(
                        0, "write_tool", Map.of("entityId", "B1001", "reason", "不想要了"))),
                plan,
                "即将执行：修改业务数据。", "WRITE",
                System.currentTimeMillis() + 60_000);
    }

    private ConfirmationService.PendingAction batchAction() {
        List<ConfirmationService.PendingWriteStep> writes = List.of(
                new ConfirmationService.PendingWriteStep(2, "write_tool", Map.of("recordId", 1)),
                new ConfirmationService.PendingWriteStep(3, "write_tool", Map.of("recordId", 2)));
        List<Map<String, Object>> plan = List.of(
                Map.of("stepNo", 0, "tool", "read_tool", "args", Map.of()),
                Map.of("stepNo", 1, "tool", "read_tool", "args", Map.of()),
                Map.of("stepNo", 2, "tool", "write_tool", "args", Map.of("recordId", 1)),
                Map.of("stepNo", 3, "tool", "write_tool", "args", Map.of("recordId", 2)));
        return new ConfirmationService.PendingAction(
                "cfm_1", "demo", "c1", "model-x", "run_1", "MULTI_WRITE", writes,
                plan, "即将执行 2 个写操作", "WRITE",
                System.currentTimeMillis() + 60_000);
    }

    private WorkflowState completedState(String result) {
        return new WorkflowState(Map.of(
                WorkflowState.RESULT, result,
                WorkflowState.FALLBACK, false,
                WorkflowState.DONE, true,
                WorkflowState.INTENT, QaIntent.TASK,
                WorkflowState.TASK_CONFIDENCE, 0.92,
                WorkflowState.TASK_CONFIDENCE_REASON, "工具证据充分",
                WorkflowState.TASK_TRACE, List.of(),
                WorkflowState.TASK_EVIDENCE, List.of(),
                WorkflowState.TASK_CLARIFY, false));
    }

    private ToolExecution execution(ToolExecution.Status status, String errorCode,
                                    String errorMessage) {
        ToolExecution execution = new ToolExecution();
        execution.setExecutionId("cfm_1");
        execution.setUserId("demo");
        execution.setConversationId("c1");
        execution.setStatus(status);
        execution.setResult("业务对象 B1001 已取消");
        execution.setErrorCode(errorCode);
        execution.setErrorMessage(errorMessage);
        return execution;
    }

    private Message message(long id) {
        Message message = new Message();
        message.setId(id);
        return message;
    }

    private void passThroughLock() {
        lenient().doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get())
                .when(sessionLock).executeWithLock(anyString(), any());
        lenient().doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get())
                .when(sessionLock).executeWithLockStrict(anyString(), any());
    }

    /** 图执行改为统一线程池提交：单测里同步执行，便于继续校验确认图入参。 */
    private void passThroughExecutor() {
        lenient().when(agentExecutor.submit(any(Callable.class))).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Callable<WorkflowState> task = (Callable<WorkflowState>) invocation.getArgument(0);
            try {
                return CompletableFuture.completedFuture(task.call());
            } catch (Exception e) {
                return CompletableFuture.failedFuture(e);
            }
        });
    }

    @Test
    @DisplayName("用户拒绝 → 只记录取消消息，不执行确认图")
    void rejectDoesNotExecuteWrite() {
        ChatResponse response = service.confirm(request(false));

        assertThat(response.getResult()).contains("已取消");
        assertThat(response.getConversationId()).isEqualTo("c1");
        assertThat(response.getBusiness().success()).isTrue();
        verify(conversationService).requireOwnership("c1", "demo");
        verify(conversationService).saveMessage(eq("c1"), eq(Message.ROLE_USER),
                contains("取消确认"), eq("model-x"), isNull());
        verify(conversationService).saveMessage(eq("c1"), eq(Message.ROLE_ASSISTANT),
                contains("未对业务数据"), eq("model-x"), isNull());
        verify(toolExecutionService).markCancelled("demo", "cfm_1");
        verify(confirmationService).finish("c1", "cfm_1");
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("取消时台账更新失败 → 仍完成用户取消，不阻断会话恢复")
    void rejectStillSucceedsWhenLedgerUpdateFails() {
        doThrow(new RuntimeException("db down"))
                .when(toolExecutionService).markCancelled("demo", "cfm_1");

        ChatResponse response = service.confirm(request(false));

        assertThat(response.getResult()).contains("已取消");
        assertThat(response.getBusiness().success()).isTrue();
        verify(confirmationService).finish("c1", "cfm_1");
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("用户确认 → 只在锁内 preview 一次，用已确认计划执行")
    @SuppressWarnings("unchecked")
    void approveExecutesConfirmedPlan() {
        ChatResponse response = service.confirm(request(true));

        assertThat(response.getResult()).isEqualTo("业务对象 B1001 已取消");
        assertThat(response.getConfidence()).isEqualTo(0.92);
        assertThat(response.getBusiness().success()).isTrue();
        verify(confirmationService, times(1)).preview("c1", "cfm_1", "demo");
        verify(conversationService).saveMessage(eq("c1"), eq(Message.ROLE_USER),
                contains("确认执行"), eq("model-x"), isNull());
        verify(conversationService).saveMessage(eq("c1"), eq(Message.ROLE_ASSISTANT),
                eq("业务对象 B1001 已取消"), eq("model-x"), isNull(), isNull());
        verifyNoInteractions(agentGraph);

        ArgumentCaptor<Map<String, Object>> inputsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(taskConfirmGraph).invoke(inputsCaptor.capture(), any(RunnableConfig.class));
        assertThat(inputsCaptor.getValue())
                .containsEntry(WorkflowState.TASK_APPROVED, true)
                .containsEntry(WorkflowState.TASK_IDEMPOTENCY_KEY, "cfm_1")
                .containsEntry(WorkflowState.CONVERSATION_ID, "c1");
        assertThat((List<Map<String, Object>>) inputsCaptor.getValue().get(WorkflowState.TASK_PLAN))
                .hasSize(1);
        verify(confirmationService).finish("c1", "cfm_1");
    }

    @Test
    @DisplayName("令牌不存在/已用过 → 404，不执行确认图")
    void missingTokenIsRejected() {
        when(confirmationService.preview("c1", "cfm_1", "demo")).thenReturn(Optional.empty());
        when(toolExecutionService.find("demo", "cfm_1")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(request(true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
        verify(conversationService).requireOwnership("c1", "demo");
        verify(sessionLock).executeWithLockStrict(eq("c1"), any());
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("令牌已删除但台账已成功 → 幂等重放上次结果，不重复执行")
    void replaySucceededExecution() {
        when(confirmationService.preview("c1", "cfm_1", "demo")).thenReturn(Optional.empty());
        ToolExecution execution = new ToolExecution();
        execution.setExecutionId("cfm_1");
        execution.setUserId("demo");
        execution.setConversationId("c1");
        execution.setStatus(ToolExecution.Status.SUCCEEDED);
        execution.setResult("已将记录 1 标记为完成");
        when(toolExecutionService.find("demo", "cfm_1")).thenReturn(Optional.of(execution));

        ChatResponse response = service.confirm(request(true));

        assertThat(response.getResult()).isEqualTo("已将记录 1 标记为完成");
        assertThat(response.getBusiness().success()).isTrue();
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("令牌已删除但台账仍为 PENDING → 视为未执行/已过期，返回 404")
    void replayPendingExecutionIsTreatedAsNotFound() {
        when(confirmationService.preview("c1", "cfm_1", "demo")).thenReturn(Optional.empty());
        when(toolExecutionService.find("demo", "cfm_1"))
                .thenReturn(Optional.of(execution(ToolExecution.Status.PENDING, null, null)));

        assertThatThrownBy(() -> service.confirm(request(true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("令牌已删除且台账为 CANCELLED → 返回 404，不重复执行")
    void replayCancelledExecutionIsTreatedAsNotFound() {
        when(confirmationService.preview("c1", "cfm_1", "demo")).thenReturn(Optional.empty());
        when(toolExecutionService.find("demo", "cfm_1"))
                .thenReturn(Optional.of(execution(ToolExecution.Status.CANCELLED, null, null)));

        assertThatThrownBy(() -> service.confirm(request(true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("会话归属校验失败 → 不加锁、不消耗确认令牌")
    void ownershipFailureDoesNotConsumeToken() {
        org.mockito.Mockito.doThrow(new BizException(ErrorCode.FORBIDDEN, "无权访问该会话"))
                .when(conversationService).requireOwnership("c1", "demo");

        assertThatThrownBy(() -> service.confirm(request(true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.FORBIDDEN.getCode());

        verifyNoInteractions(sessionLock, taskConfirmGraph);
    }

    @Test
    @DisplayName("会话锁冲突 → 不消耗确认令牌，用户仍可重试")
    void lockConflictDoesNotConsumeToken() {
        doThrow(new BizException(ErrorCode.AGENT_SESSION_LOCKED))
                .when(sessionLock).executeWithLockStrict(anyString(), any());

        assertThatThrownBy(() -> service.confirm(request(true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.AGENT_SESSION_LOCKED.getCode());

        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("确认执行失败 → 写失败回复、保留令牌供幂等重试")
    void executionFailureClosesTurn() {
        when(taskConfirmGraph.invoke(anyMap(), any(RunnableConfig.class)))
                .thenThrow(new IllegalStateException("db down"));

        assertThatThrownBy(() -> service.confirm(request(true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.AGENT_EXECUTION_FAILED.getCode());
        verify(conversationService).saveMessage(eq("c1"), eq(Message.ROLE_USER),
                contains("确认执行"), eq("model-x"), isNull());
        verify(conversationService).saveMessage(eq("c1"), eq(Message.ROLE_ASSISTANT),
                contains("确认操作执行失败"), eq("model-x"), isNull());
        verify(confirmationService, never()).finish("c1", "cfm_1");
    }

    @Test
    @DisplayName("写工具返回失败 → 保留令牌，业务结果返回失败原因")
    void toolFailureKeepsTokenForRetry() {
        when(taskConfirmGraph.invoke(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(completedState("写操作失败：数据库暂时不可用")));
        when(toolExecutionService.find("demo", "cfm_1"))
                .thenReturn(Optional.of(execution(ToolExecution.Status.FAILED, "DB_DOWN", "数据库不可用")));

        ChatResponse response = service.confirm(request(true));

        assertThat(response.getResult()).contains("写操作失败");
        assertThat(response.getBusiness().success()).isFalse();
        assertThat(response.getBusiness().errorCode()).isEqualTo("DB_DOWN");
        assertThat(response.getBusiness().errorMessage()).isEqualTo("数据库不可用");
        verify(confirmationService, never()).finish("c1", "cfm_1");
    }

    @Test
    @DisplayName("存在待确认令牌 → 新消息先取消旧确认，再按新意图执行")
    void chatCancelsPendingConfirmationBeforeNewIntent() {
        when(conversationService.ensureConversation("demo", "c1", "继续")).thenReturn("c1");
        when(modelRegistry.resolve(null)).thenReturn("model-x");
        when(conversationService.saveMessage(eq("c1"), eq(Message.ROLE_USER), eq("继续"),
                eq("model-x"), isNull())).thenReturn(message(1L));
        when(conversationService.rewriteHistoryView(eq("c1"), anyInt(), anyInt()))
                .thenReturn(List.of());
        when(segmentService.listSegments("c1")).thenReturn(List.of());
        when(queryRewriter.rewrite(eq("继续"), eq(List.of()), eq(List.of()), eq("model-x")))
                .thenReturn(new RewriteResult("继续", null, false));
        ConversationSummary segment = new ConversationSummary();
        segment.setSegmentId(1);
        when(segmentService.resolveSegment(eq("c1"), eq(List.of()), any(RewriteResult.class),
                eq(1L))).thenReturn(segment);
        when(conversationService.generationHistoryView(eq("c1"), anyInt(), anyInt(), anyInt(),
                anyInt())).thenReturn(List.of());
        when(agentGraph.invoke(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(completedState("这是新意图的回答")));

        ChatResponse response = service.chat(chatRequest());

        assertThat(response.getResult()).isEqualTo("这是新意图的回答");
        verify(confirmationService).cancelPending("c1", "demo", "USER_NEW_MESSAGE");
        verify(taskRunService).cancelByConversation("demo", "c1");
        verify(agentGraph).invoke(anyMap(), any(RunnableConfig.class));
    }

    @Test
    @DisplayName("旧令牌还在 Redis 但台账已取消 → 不执行确认图")
    void cancelledLedgerBlocksStaleConfirm() {
        when(toolExecutionService.find("demo", "cfm_1")).thenReturn(Optional.of(
                execution(ToolExecution.Status.CANCELLED, null, null)));

        assertThatThrownBy(() -> service.confirm(request(true)))
                .isInstanceOf(BizException.class);

        verify(confirmationService).finish("c1", "cfm_1");
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("批量任务取消 -> run 和每个写步骤台账都取消")
    void rejectBatchCancelsRunAndStepLedgers() {
        when(confirmationService.preview("c1", "cfm_1", "demo"))
                .thenReturn(Optional.of(batchAction()));
        when(taskRunService.cancel("demo", "run_1")).thenReturn(true);

        ChatResponse response = service.confirm(request(false));

        assertThat(response.getResult()).contains("已取消");
        assertThat(response.getTaskRunId()).isEqualTo("run_1");
        assertThat(response.getTaskStatus()).isEqualTo(TaskRun.Status.CANCELLED.name());
        verify(taskRunService).cancel("demo", "run_1");
        verify(toolExecutionService).markCancelled("demo", "run_1:2");
        verify(toolExecutionService).markCancelled("demo", "run_1:3");
        verify(toolExecutionService, never()).markCancelled("demo", "cfm_1");
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("任务进度查询 -> 返回 run 状态和每步结果")
    void taskStatusReturnsRunAndSteps() {
        TaskRun run = new TaskRun();
        run.setId("run_1");
        run.setConversationId("c1");
        run.setWorkflowId("wf");
        run.setStatus(TaskRun.Status.FAILED);
        run.setCurrentStep(2);
        run.setErrorCode("BIZ_ERROR");
        run.setErrorMessage("业务拒绝");
        TaskStep step = new TaskStep();
        step.setStepNo(1);
        step.setStageId("complete");
        step.setToolName("write_tool");
        step.setSideEffect("WRITE");
        step.setStatus(TaskStep.Status.FAILED);
        step.setErrorCode("BIZ_ERROR");
        step.setErrorMessage("业务拒绝");
        step.setArgsJson("{\"recordId\":7}");
        step.setCostMs(12L);
        step.setAttempts(1);
        when(taskRunService.find("demo", "run_1")).thenReturn(Optional.of(run));
        when(taskRunService.steps("run_1")).thenReturn(List.of(step));

        var dto = service.taskStatus("run_1");

        assertThat(dto.status()).isEqualTo(TaskRun.Status.FAILED.name());
        assertThat(dto.currentStep()).isEqualTo(2);
        assertThat(dto.steps()).hasSize(1);
        assertThat(dto.steps().get(0).args()).containsEntry("recordId", 7);
        assertThat(dto.steps().get(0).errorCode()).isEqualTo("BIZ_ERROR");
    }

    @Test
    @DisplayName("确认计划指纹不一致 -> 拒绝执行并删除令牌")
    void planHashMismatchBlocksConfirm() {
        when(confirmationService.preview("c1", "cfm_1", "demo"))
                .thenReturn(Optional.of(batchAction()));
        TaskRun run = new TaskRun();
        run.setId("run_1");
        run.setStatus(TaskRun.Status.WAITING_CONFIRM);
        run.setPlanHash("expected");
        when(taskRunService.find("demo", "run_1")).thenReturn(Optional.of(run));
        when(taskRunService.planHash(anyString())).thenReturn("tampered");

        assertThatThrownBy(() -> service.confirm(request(true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.AGENT_EXECUTION_FAILED.getCode());

        verify(confirmationService).finish("c1", "cfm_1");
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("任务执行中 -> 取消/重复确认返回执行中，不进入确认图")
    void runningTaskBlocksConfirmAndReject() {
        when(confirmationService.preview("c1", "cfm_1", "demo"))
                .thenReturn(Optional.of(batchAction()));
        TaskRun run = new TaskRun();
        run.setId("run_1");
        run.setStatus(TaskRun.Status.RUNNING);
        when(taskRunService.find("demo", "run_1")).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> service.confirm(request(false)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.CONFIRMATION_IN_PROGRESS.getCode());

        verifyNoInteractions(taskConfirmGraph);
    }
}
