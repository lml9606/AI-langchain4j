package com.example.langchain4j.service;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.ConfirmRequest;
import com.example.langchain4j.dto.response.ChatResponse;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.guard.SessionLock;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.repository.entity.Message;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 确认接口服务层单测：令牌消费、拒绝不执行、确认执行不重新规划、重复提交拒绝。
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
    @Mock
    private AgentProperties properties;
    @Mock
    private AgentMetrics metrics;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private ConfirmationService confirmationService;

    private AgentService service;

    @BeforeEach
    void setUp() {
        service = new AgentService(agentGraph, taskConfirmGraph, rateLimiter, sessionLock,
                conversationService, queryRewriter, segmentService, auditService, summaryService,
                modelRegistry, ticketService, agentExecutor, properties, metrics,
                promptTemplateService, confirmationService);
        UserContext.set("demo", "USER");
        lenient().when(taskConfirmGraph.invoke(anyMap(), any(RunnableConfig.class)))
                .thenReturn(Optional.of(completedState("业务对象 B1001 已取消")));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    private ConfirmRequest request(boolean approved) {
        ConfirmRequest request = new ConfirmRequest();
        request.setConfirmationId("cfm_1");
        request.setApproved(approved);
        return request;
    }

    private ConfirmationService.PendingAction action() {
        List<Map<String, Object>> plan = List.of(Map.of(
                "tool", "write_tool",
                "args", Map.of("entityId", "B1001", "reason", "不想要了"),
                "purpose", "修改业务数据"));
        return new ConfirmationService.PendingAction(
                "cfm_1", "demo", "c1", "model-x", "write_tool", plan,
                "即将执行：修改业务数据。", "WRITE",
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

    private void passThroughLock() {
        lenient().doAnswer(invocation -> ((java.util.function.Supplier<?>) invocation.getArgument(1)).get())
                .when(sessionLock).executeWithLock(anyString(), any());
    }

    @Test
    @DisplayName("用户拒绝 → 只记录取消消息，不执行确认图")
    void rejectDoesNotExecuteWrite() {
        when(confirmationService.consume("cfm_1", "demo")).thenReturn(Optional.of(action()));
        passThroughLock();

        ChatResponse response = service.confirm(request(false));

        assertThat(response.getResult()).contains("已取消");
        assertThat(response.getConversationId()).isEqualTo("c1");
        verify(conversationService).requireOwnership("c1", "demo");
        verify(conversationService).saveMessage(eq("c1"), eq(Message.ROLE_USER),
                contains("取消确认"), eq("model-x"), isNull());
        verify(conversationService).saveMessage(eq("c1"), eq(Message.ROLE_ASSISTANT),
                contains("未对业务数据"), eq("model-x"), isNull());
        verifyNoInteractions(taskConfirmGraph);
    }

    @Test
    @DisplayName("用户确认 → 用已确认计划执行，不重新跑 intent/task_plan")
    @SuppressWarnings("unchecked")
    void approveExecutesConfirmedPlan() {
        when(confirmationService.consume("cfm_1", "demo")).thenReturn(Optional.of(action()));
        passThroughLock();

        ChatResponse response = service.confirm(request(true));

        assertThat(response.getResult()).isEqualTo("业务对象 B1001 已取消");
        assertThat(response.getConfidence()).isEqualTo(0.92);
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
    }

    @Test
    @DisplayName("令牌不存在/已用过 → 404，且不加锁、不执行")
    void missingTokenIsRejected() {
        when(confirmationService.consume("cfm_1", "demo")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.confirm(request(true)))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
        verifyNoInteractions(sessionLock, taskConfirmGraph);
        verify(conversationService, never()).requireOwnership(anyString(), anyString());
    }

    @Test
    @DisplayName("确认执行失败 → 写失败回复并向上抛业务异常，令牌仍已被消费")
    void executionFailureClosesTurn() {
        when(confirmationService.consume("cfm_1", "demo")).thenReturn(Optional.of(action()));
        passThroughLock();
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
    }
}
