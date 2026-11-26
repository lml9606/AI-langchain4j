package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.tool.api.model.ToolContext;
import com.example.langchain4j.tool.api.model.ToolResult;
import com.example.langchain4j.tool.execution.ToolExecution;
import com.example.langchain4j.tool.execution.ToolExecutionService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 写执行模板测评：验证幂等闸门、成功/失败台账和业务异常回滚语义。 */
@ExtendWith(MockitoExtension.class)
class WriteExecutionTemplateTest {

    @Mock
    private ToolExecutionService executionService;

    private WriteExecutionTemplate template;
    private ToolContext context;
    private JsonNode args;

    /** 准备固定用户、确认标识和参数，保证每条用例可重复。 */
    @BeforeEach
    void setUp() {
        template = new WriteExecutionTemplate(executionService);
        context = new ToolContext("eval-user", "c1", true, "cfm_1");
        args = JsonUtil.fromJson("{\"recordId\":\"R1\"}", JsonNode.class);
    }

    /** 首次获得执行权时调用业务写入，并把成功结果写入台账。 */
    @Test
    void acquiredWriteExecutesAndMarksSucceeded() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.ACQUIRED, null));
        AtomicInteger calls = new AtomicInteger();

        ToolResult result = template.execute(context, "complete_demo_record", args, () -> {
            calls.incrementAndGet();
            return ToolResult.success(null, "done", java.util.List.of(), 1);
        });

        assertThat(result.success()).isTrue();
        assertThat(calls.get()).isEqualTo(1);
        ArgumentCaptor<ToolExecution> executionCaptor = ArgumentCaptor.forClass(ToolExecution.class);
        verify(executionService).begin(executionCaptor.capture());
        assertThat(executionCaptor.getValue().getExecutionId()).isEqualTo("cfm_1");
        assertThat(executionCaptor.getValue().getUserId()).isEqualTo("eval-user");
        assertThat(executionCaptor.getValue().getArgsHash()).isNotBlank();
        verify(executionService).succeed("eval-user", "cfm_1", "done");
    }

    /** 业务返回失败时记录失败错误码和原因，不能标记成功。 */
    @Test
    void failedBusinessResultMarksFailed() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.ACQUIRED, null));

        ToolResult result = template.execute(context, "complete_demo_record", args,
                () -> ToolResult.failure("BIZ_FAILED", "record missing", 1));

        assertThat(result.success()).isFalse();
        verify(executionService).fail("eval-user", "cfm_1", "BIZ_FAILED", "record missing");
        verify(executionService, never()).succeed(anyString(), anyString(), anyString());
    }

    /** 业务抛异常时向事务外层传播，由事务回滚，测试层不得补记成功或失败。 */
    @Test
    void businessExceptionPropagatesWithoutFinalLedgerUpdate() {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.ACQUIRED, null));

        assertThatThrownBy(() -> template.execute(context, "complete_demo_record", args,
                () -> {
                    throw new IllegalStateException("db down");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");
        verify(executionService, never()).succeed(anyString(), anyString(), anyString());
        verify(executionService, never()).fail(anyString(), anyString(), anyString(), anyString());
    }

    /** 已完成幂等键直接重放上次摘要，不再次调用业务写入。 */
    @Test
    void replayReturnsPreviousResultWithoutBusinessCall() throws Exception {
        ToolExecution existing = new ToolExecution();
        existing.setResult("previous result");
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.REPLAY, existing));
        AtomicInteger calls = new AtomicInteger();

        ToolResult result = template.execute(context, "complete_demo_record", args, () -> {
            calls.incrementAndGet();
            return ToolResult.success(null, "new", java.util.List.of(), 1);
        });

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("previous result");
        assertThat(calls.get()).isZero();
    }

    /** 同键执行中返回稳定错误码，绝不并发进入业务写入。 */
    @Test
    void inProgressGateRejectsBusinessCall() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.IN_PROGRESS, null));
        AtomicInteger calls = new AtomicInteger();

        ToolResult result = template.execute(context, "complete_demo_record", args, () -> {
            calls.incrementAndGet();
            return ToolResult.success(null, "done", java.util.List.of(), 1);
        });

        assertThat(result.errorCode()).isEqualTo("IN_PROGRESS");
        assertThat(calls.get()).isZero();
    }

    /** 同键不同参数必须返回冲突，不能把新操作当作旧操作重放。 */
    @Test
    void parameterConflictIsRejected() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.CONFLICT, null));
        AtomicInteger calls = new AtomicInteger();
        Callable<ToolResult> business = () -> {
            calls.incrementAndGet();
            return ToolResult.success(null, "done", java.util.List.of(), 1);
        };

        ToolResult result = template.execute(context, "complete_demo_record", args, business);

        assertThat(result.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
        assertThat(calls.get()).isZero();
    }
}
