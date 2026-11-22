package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.service.ToolExecutionService;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写执行模板单测：台账闸门与业务执行的分工。
 */
@ExtendWith(MockitoExtension.class)
class WriteExecutionTemplateTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL = "complete_demo_record";
    private static final ToolContext CONTEXT =
            new ToolContext("demo", "c1", true, "cfm_1");

    @Mock
    private ToolExecutionService executionService;

    private WriteExecutionTemplate template;

    @BeforeEach
    void setUp() {
        template = new WriteExecutionTemplate(executionService);
    }

    @Test
    @DisplayName("已成功执行：直接返回上次结果，不重复业务写入")
    void replayReturnsPreviousResult() throws Exception {
        ToolExecution existing = new ToolExecution();
        existing.setStatus(ToolExecution.Status.SUCCEEDED);
        existing.setResult("上次已处理");
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.REPLAY, existing));
        AtomicBoolean invoked = new AtomicBoolean();

        ToolResult result = template.execute(CONTEXT, TOOL, args("{\"recordId\":1}"), () -> {
            invoked.set(true);
            return ToolResult.success(Map.of(), "不应执行", List.of(), 0);
        });

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("上次已处理");
        assertThat(invoked).isFalse();
    }

    @Test
    @DisplayName("并发执行中：返回 IN_PROGRESS，不触碰业务")
    void inProgressRejectsSecondExecution() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.IN_PROGRESS, null));

        ToolResult result = template.execute(CONTEXT, TOOL, args("{\"recordId\":1}"), () -> {
            throw new AssertionError("业务不应执行");
        });

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("同一幂等键换参数：CONFLICT，不触碰业务")
    void argsConflictRejectsExecution() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.CONFLICT, null));

        ToolResult result = template.execute(CONTEXT, TOOL, args("{\"recordId\":2}"), () -> {
            throw new AssertionError("业务不应执行");
        });

        assertThat(result.success()).isFalse();
        assertThat(result.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }

    @Test
    @DisplayName("抢到执行权且业务成功：标记 SUCCEEDED")
    void acquiredAndSuccessMarksSucceeded() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.ACQUIRED, null));

        ToolResult result = template.execute(CONTEXT, TOOL, args("{\"recordId\":1}"),
                () -> ToolResult.success(Map.of("recordId", 1), "已完成", List.of(), 3));

        assertThat(result.success()).isTrue();
        verify(executionService).succeed("demo", "cfm_1", "已完成");
        verify(executionService, never()).fail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("抢到执行权但业务返回失败：标记 FAILED 并让后续可重试")
    void acquiredAndFailureMarksFailed() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.ACQUIRED, null));

        ToolResult result = template.execute(CONTEXT, TOOL, args("{\"recordId\":1}"),
                () -> ToolResult.failure("NOT_FOUND", "记录不存在", 2));

        assertThat(result.success()).isFalse();
        verify(executionService).fail("demo", "cfm_1", "NOT_FOUND", "记录不存在");
        verify(executionService, never()).succeed(anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("业务抛异常：异常向上抛出，交由事务整体回滚")
    void businessExceptionPropagates() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.ACQUIRED, null));

        assertThatThrownBy(() -> template.execute(CONTEXT, TOOL, args("{\"recordId\":1}"),
                () -> {
                    throw new IllegalStateException("db down");
                }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("db down");

        verify(executionService, never()).succeed(anyString(), anyString(), anyString());
        verify(executionService, never()).fail(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("参数指纹：同一幂等键换参数会生成不同指纹")
    void argsHashDetectsDifferentArguments() throws Exception {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(
                        ToolExecutionService.BeginStatus.ACQUIRED, null));

        template.execute(CONTEXT, TOOL, args("{\"recordId\":1}"),
                () -> ToolResult.success(Map.of(), "ok-1", List.of(), 0));
        template.execute(CONTEXT, TOOL, args("{\"recordId\":2}"),
                () -> ToolResult.success(Map.of(), "ok-2", List.of(), 0));

        ArgumentCaptor<ToolExecution> captor = ArgumentCaptor.forClass(ToolExecution.class);
        verify(executionService, org.mockito.Mockito.times(2)).begin(captor.capture());
        assertThat(captor.getAllValues().get(0).getArgsHash())
                .isNotEqualTo(captor.getAllValues().get(1).getArgsHash());
    }

    private JsonNode args(String json) throws Exception {
        return MAPPER.readTree(json);
    }
}
