package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.auth.UserContext;
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
import java.util.Set;
import java.util.concurrent.CountDownLatch;
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
 * 工具执行器单测：写权限、角色、参数、限流、线程池拒绝、超时与成功执行。
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL_NAME = "demo_tool";
    private static final ToolContext CONTEXT = new ToolContext("demo", "c1");

    @Mock
    private ToolRegistry registry;
    @Mock
    private ToolAccessPolicy accessPolicy;
    @Mock
    private AuditService auditService;
    @Mock
    private AgentMetrics metrics;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private AgentTool tool;

    private AgentProperties properties;
    private ThreadPoolTaskExecutor taskExecutor;
    private ToolExecutor executor;

    @BeforeEach
    void setUp() throws Exception {
        UserContext.set("demo", "USER");
        properties = new AgentProperties();
        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(2);
        taskExecutor.setMaxPoolSize(2);
        taskExecutor.setQueueCapacity(10);
        taskExecutor.setThreadNamePrefix("tool-test-");
        taskExecutor.initialize();
        executor = new ToolExecutor(registry, accessPolicy, auditService, metrics, taskExecutor,
                properties, rateLimiter);
        lenient().when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        lenient().when(accessPolicy.check(any(ToolDefinition.class), any())).thenReturn(
                new ToolAccessPolicy.Decision(true, ToolAccessPolicy.Status.ALLOWED, "", ""));
        lenient().when(registry.findRegistered(TOOL_NAME)).thenAnswer(invocation ->
                Optional.of(new ToolRegistry.RegisteredTool(tool, tool.definition())));
        lenient().when(tool.execute(any(), any(JsonNode.class)))
                .thenReturn(ToolResult.success(Map.of("ok", true), "成功", List.of(), 1));
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        taskExecutor.shutdown();
    }

    private ToolDefinition definition(ToolSideEffect sideEffect, boolean requireEntityId) {
        String schema = requireEntityId
                ? "{\"type\":\"object\",\"required\":[\"entityId\"],"
                        + "\"properties\":{\"entityId\":{\"type\":\"string\"}}}"
                : "{\"type\":\"object\",\"properties\":{}}";
        return new ToolDefinition(TOOL_NAME, "演示工具", schema, sideEffect, 3000);
    }

    @Test
    @DisplayName("写工具在全局关闭时 → FORBIDDEN_WRITE，且不执行")
    void rejectWriteToolWhenWriteDisabled() throws Exception {
        when(accessPolicy.check(any(ToolDefinition.class), any())).thenReturn(
                new ToolAccessPolicy.Decision(false, ToolAccessPolicy.Status.WRITE_DISABLED,
                        TOOL_NAME, "写操作在当前环境已禁用"));
        when(tool.definition()).thenReturn(definition(ToolSideEffect.WRITE, false));

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("FORBIDDEN_WRITE");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("缺少必填参数 → INVALID_ARGS，且不执行")
    void rejectMissingRequiredArg() throws Exception {
        when(tool.definition()).thenReturn(definition(ToolSideEffect.READ_ONLY, true));

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("INVALID_ARGS");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("参数不符合 Schema（枚举/类型）→ INVALID_ARGS，且不执行")
    void rejectSchemaViolation() throws Exception {
        when(tool.definition()).thenReturn(new ToolDefinition(TOOL_NAME, "状态查询",
                "{\"type\":\"object\",\"additionalProperties\":false,"
                        + "\"properties\":{\"status\":{\"type\":\"string\","
                        + "\"enum\":[\"OPEN\",\"DONE\"]}}}",
                ToolSideEffect.READ_ONLY, 3000));

        ToolResult badEnum = executor.invoke(CONTEXT, TOOL_NAME,
                MAPPER.readTree("{\"status\":\"BAD\"}"));
        ToolResult badType = executor.invoke(CONTEXT, TOOL_NAME,
                MAPPER.readTree("{\"status\":1}"));

        assertThat(badEnum.errorCode()).isEqualTo("INVALID_ARGS");
        assertThat(badType.errorCode()).isEqualTo("INVALID_ARGS");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("正常只读调用 → 执行并返回结果")
    void invokeReadOnlyTool() throws Exception {
        when(tool.definition()).thenReturn(definition(ToolSideEffect.READ_ONLY, true));

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME,
                MAPPER.readTree("{\"entityId\":\"B1001\"}"));

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("成功");
    }

    @Test
    @DisplayName("写工具未确认 → CONFIRMATION_REQUIRED，且不执行")
    void rejectUnapprovedWriteTool() throws Exception {
        properties.getTool().setWriteEnabled(true);
        when(tool.definition()).thenReturn(definition(ToolSideEffect.WRITE, false));

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("CONFIRMATION_REQUIRED");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("写工具已确认且开启 → 正常执行")
    void invokeApprovedWriteTool() throws Exception {
        properties.getTool().setWriteEnabled(true);
        when(tool.definition()).thenReturn(definition(ToolSideEffect.WRITE, false));
        ToolContext approved = new ToolContext("demo", "c1", true, "cfm_1");

        ToolResult result = executor.invoke(approved, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.success()).isTrue();
        verify(tool).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("写工具已确认但没有幂等键 → 拒绝执行（防重复落库）")
    void rejectWriteToolWithoutIdempotencyKey() throws Exception {
        properties.getTool().setWriteEnabled(true);
        when(tool.definition()).thenReturn(definition(ToolSideEffect.WRITE, false));
        ToolContext approved = new ToolContext("demo", "c1", true, "");

        ToolResult result = executor.invoke(approved, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("角色不匹配 → FORBIDDEN_TOOL，且不执行")
    void rejectToolWhenRoleNotAllowed() throws Exception {
        ToolDefinition adminOnly = new ToolDefinition(TOOL_NAME, "管理员工具",
                "{\"type\":\"object\",\"properties\":{}}", ToolSideEffect.READ_ONLY,
                3000, new ToolDefinition.ToolPolicy(true, 0, Set.of("ADMIN")));
        when(accessPolicy.check(any(ToolDefinition.class), any())).thenReturn(
                new ToolAccessPolicy.Decision(false, ToolAccessPolicy.Status.PERMISSION_REQUIRED,
                        TOOL_NAME, "当前角色无权执行该任务"));
        when(tool.definition()).thenReturn(adminOnly);

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("FORBIDDEN_TOOL");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("DANGEROUS 工具 → 永远拒绝执行")
    void rejectDangerousTool() throws Exception {
        properties.getTool().setWriteEnabled(true);
        when(accessPolicy.check(any(ToolDefinition.class), any())).thenReturn(
                new ToolAccessPolicy.Decision(false, ToolAccessPolicy.Status.DANGEROUS,
                        TOOL_NAME, "任务包含禁止自动执行的危险工具"));
        when(tool.definition()).thenReturn(definition(ToolSideEffect.DANGEROUS, false));
        ToolContext approved = new ToolContext("demo", "c1", true, "cfm_1");

        ToolResult result = executor.invoke(approved, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("FORBIDDEN_TOOL");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    @Test
    @DisplayName("单用户单工具超过每分钟上限 → RATE_LIMITED")
    void rateLimitPerUser() throws Exception {
        properties.getTool().setMaxCallsPerMinute(1);
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true, false);
        when(tool.definition()).thenReturn(definition(ToolSideEffect.READ_ONLY, false));

        ToolResult first = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));
        ToolResult second = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(first.success()).isTrue();
        assertThat(second.errorCode()).isEqualTo("RATE_LIMITED");
        verify(rateLimiter, times(2)).tryAcquire("tool:demo:" + TOOL_NAME, 1);
    }

    @Test
    @DisplayName("工具队列满 → OVERLOADED，而不是向图抛裸异常")
    void rejectWhenToolQueueFull() throws Exception {
        when(tool.definition()).thenReturn(definition(ToolSideEffect.READ_ONLY, false));
        ThreadPoolTaskExecutor saturated = new ThreadPoolTaskExecutor();
        saturated.setCorePoolSize(1);
        saturated.setMaxPoolSize(1);
        saturated.setQueueCapacity(0);
        saturated.initialize();
        CountDownLatch blocker = new CountDownLatch(1);
        try {
            saturated.execute(() -> {
                try {
                    blocker.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });

            ToolExecutor overloaded = new ToolExecutor(registry, accessPolicy, auditService,
                    metrics, saturated, properties, rateLimiter);
            ToolResult result = overloaded.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

            assertThat(result.errorCode()).isEqualTo("OVERLOADED");
        } finally {
            blocker.countDown();
            saturated.shutdown();
        }
    }

    @Test
    @DisplayName("工具执行超时 → TIMEOUT 结构化返回")
    void timeoutReturnsStructuredFailure() throws Exception {
        ToolDefinition fastTimeout = new ToolDefinition(TOOL_NAME, "慢工具",
                "{\"type\":\"object\",\"properties\":{}}", ToolSideEffect.READ_ONLY, 50);
        when(tool.definition()).thenReturn(fastTimeout);
        when(tool.execute(any(), any(JsonNode.class))).thenAnswer(invocation -> {
            Thread.sleep(500);
            return ToolResult.success(Map.of(), "晚到结果", List.of(), 500);
        });

        ToolResult result = executor.invoke(CONTEXT, TOOL_NAME, MAPPER.readTree("{}"));

        assertThat(result.errorCode()).isEqualTo("TIMEOUT");
    }
}
