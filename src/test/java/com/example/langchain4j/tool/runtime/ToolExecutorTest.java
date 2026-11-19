package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.service.AuditService;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具执行器单测：只读门禁、必填参数、限流与成功执行是工具权限的四道防线。
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_NAME = "demo_tool";
    private static final ToolContext CONTEXT = new ToolContext("demo", "c1");

    @Mock
    private ToolRegistry registry;
    @Mock
    private AuditService auditService;
    @Mock
    private AgentMetrics metrics;
    @Mock
    private ThreadPoolTaskExecutor taskExecutor;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private AgentTool tool;

    private AgentProperties properties;
    private ToolExecutor executor;
    private ThreadPoolExecutor pool;

    @BeforeEach
    void setUp() throws Exception {
        properties = new AgentProperties();
        pool = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>());
        lenient().when(taskExecutor.getThreadPoolExecutor()).thenReturn(pool);
        executor = new ToolExecutor(registry, auditService, metrics, taskExecutor, properties, rateLimiter);
        lenient().when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        lenient().when(registry.find(TOOL_NAME)).thenReturn(Optional.of(tool));
        lenient().when(tool.execute(any(), any(JsonNode.class)))
                .thenReturn(ToolResult.success(Map.of("ok", true), "成功", List.of(), 1));
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
    }

    private ToolDefinition definition(ToolSideEffect sideEffect, List<String> required) {
        return new ToolDefinition(TOOL_NAME, "演示工具", "{}", required, sideEffect, 3000);
    }

    @Test
    @DisplayName("只读模式 → WRITE 工具被拒绝，且不执行")
    void rejectWriteToolInReadOnlyMode() throws Exception {
        when(tool.definition()).thenReturn(definition(ToolSideEffect.WRITE, List.of()));

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("FORBIDDEN_WRITE");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("缺少必填参数 → INVALID_ARGS，且不执行")
    void rejectMissingRequiredArg() throws Exception {
        when(tool.definition()).thenReturn(definition(ToolSideEffect.READ_ONLY, List.of("entityId")));

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("INVALID_ARGS");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("正常只读调用 → 执行并返回结果")
    void invokeReadOnlyTool() throws Exception {
        when(tool.definition()).thenReturn(definition(ToolSideEffect.READ_ONLY, List.of("entityId")));

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{\"entityId\":\"B1001\"}"));

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("成功");
    }

    @Test
    @DisplayName("写工具未确认 → CONFIRMATION_REQUIRED，且不执行")
    void rejectUnapprovedWriteTool() throws Exception {
        properties.getTool().setReadOnly(false);
        properties.getTool().setAllowedWriteTools(List.of(TOOL_NAME));
        when(tool.definition()).thenReturn(definition(ToolSideEffect.WRITE, List.of()));

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("CONFIRMATION_REQUIRED");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("写工具不在白名单 → FORBIDDEN_WRITE，即使已确认也拒绝")
    void rejectWriteToolOutsideAllowlist() throws Exception {
        properties.getTool().setReadOnly(false);
        properties.getTool().setAllowedWriteTools(List.of("other_tool"));
        when(tool.definition()).thenReturn(definition(ToolSideEffect.WRITE, List.of()));
        ToolContext approved = new ToolContext("demo", "c1", true, "cfm_1");

        ToolResult result = executor.invoke(approved, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("FORBIDDEN_WRITE");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("写工具已确认且在白名单 → 正常执行")
    void invokeApprovedWriteTool() throws Exception {
        properties.getTool().setReadOnly(false);
        properties.getTool().setAllowedWriteTools(List.of(TOOL_NAME));
        when(tool.definition()).thenReturn(definition(ToolSideEffect.WRITE, List.of()));
        ToolContext approved = new ToolContext("demo", "c1", true, "cfm_1");

        ToolResult result = executor.invoke(approved, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.success()).isTrue();
        verify(tool).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("写工具已确认但没有幂等键 → 拒绝执行（防重复落库）")
    void rejectWriteToolWithoutIdempotencyKey() throws Exception {
        properties.getTool().setReadOnly(false);
        properties.getTool().setAllowedWriteTools(List.of(TOOL_NAME));
        when(tool.definition()).thenReturn(definition(ToolSideEffect.WRITE, List.of()));
        ToolContext approved = new ToolContext("demo", "c1", true, "");

        ToolResult result = executor.invoke(approved, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("单用户超过每分钟上限 → RATE_LIMITED")
    void rateLimitPerUser() throws Exception {
        properties.getTool().setMaxCallsPerMinute(1);
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true, false);
        when(tool.definition()).thenReturn(definition(ToolSideEffect.READ_ONLY, List.of()));

        ToolResult first = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));
        ToolResult second = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(first.success()).isTrue();
        assertThat(second.errorCode()).isEqualTo("RATE_LIMITED");
        verify(rateLimiter, times(2)).tryAcquire("tool:demo", 1);
    }
}
