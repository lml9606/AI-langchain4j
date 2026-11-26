package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.auth.security.UserContext;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.tool.api.contract.AgentTool;
import com.example.langchain4j.tool.api.model.ToolContext;
import com.example.langchain4j.tool.api.model.ToolDefinition;
import com.example.langchain4j.tool.api.model.ToolResult;
import com.example.langchain4j.tool.api.model.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.Callable;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工具最终门禁测评：验证权限、写确认、幂等键、Schema 和限流不能被模型绕过。 */
@ExtendWith(MockitoExtension.class)
class ToolExecutorTest {

    private static final String TOOL = "demo_tool";

    @Mock
    private ToolRegistry registry;
    @Mock
    private AgentMetrics metrics;
    @Mock
    private RateLimiter rateLimiter;
    @Mock
    private WriteExecutionTemplate writeExecutionTemplate;
    @Mock
    private AgentTool tool;

    private AgentProperties properties;
    private ThreadPoolTaskExecutor taskExecutor;
    private ToolExecutor executor;

    /** 组装真实线程池与访问策略，只替换外部依赖和工具实现。 */
    @BeforeEach
    void setUp() throws Exception {
        properties = new AgentProperties();
        properties.getTool().setTimeoutMs(1_000);
        taskExecutor = new ThreadPoolTaskExecutor();
        taskExecutor.setCorePoolSize(1);
        taskExecutor.setMaxPoolSize(1);
        taskExecutor.setQueueCapacity(10);
        taskExecutor.initialize();
        executor = new ToolExecutor(registry, new ToolAccessPolicy(registry, properties),
                metrics, taskExecutor, properties, rateLimiter, writeExecutionTemplate);
        UserContext.set("eval-user", "USER");
        lenient().when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(true);
        lenient().when(tool.execute(any(), any(JsonNode.class)))
                .thenReturn(ToolResult.success("ok", "成功", List.of(), 1));
    }

    /** 清理线程池和用户上下文，避免测试间身份或线程泄漏。 */
    @AfterEach
    void tearDown() {
        UserContext.clear();
        taskExecutor.shutdown();
    }

    /** 未注册工具必须直接失败，不能进入权限或执行阶段。 */
    @Test
    void unknownToolIsRejected() throws Exception {
        ToolResult result = executor.invoke(readContext(), TOOL, objectNode("{}"));

        assertThat(result.errorCode()).isEqualTo("UNKNOWN_TOOL");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    /** 默认关闭写开关时，即使上下文声称已确认也必须拒绝写工具。 */
    @Test
    void writeDisabledEnvironmentRejectsApprovedWrite() throws Exception {
        register(definition(ToolSideEffect.WRITE, "{}", ToolDefinition.ToolPolicy.defaults()));

        ToolResult result = executor.invoke(
                new ToolContext("eval-user", "c1", true, "cfm_1"), TOOL, objectNode("{}"));

        assertThat(result.errorCode()).isEqualTo("FORBIDDEN_WRITE");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    /** 角色不匹配时拒绝执行，不能依赖前置规划层过滤。 */
    @Test
    void roleRestrictedToolRejectsOtherRole() throws Exception {
        register(definition(ToolSideEffect.READ_ONLY, "{}",
                new ToolDefinition.ToolPolicy(true, 0, Set.of("ADMIN"))));

        ToolResult result = executor.invoke(readContext(), TOOL, objectNode("{}"));

        assertThat(result.errorCode()).isEqualTo("FORBIDDEN_TOOL");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    /** 高危工具必须无条件拒绝，即使已经确认和开启写开关。 */
    @Test
    void dangerousToolIsRejected() throws Exception {
        properties.getTool().setWriteEnabled(true);
        register(definition(ToolSideEffect.DANGEROUS, "{}", ToolDefinition.ToolPolicy.defaults()));

        ToolResult result = executor.invoke(
                new ToolContext("eval-user", "c1", true, "cfm_1"), TOOL, objectNode("{}"));

        assertThat(result.errorCode()).isEqualTo("FORBIDDEN_TOOL");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    /** 写工具未确认时必须返回确认错误，且不能触碰写执行模板。 */
    @Test
    void unapprovedWriteRequiresConfirmation() throws Exception {
        properties.getTool().setWriteEnabled(true);
        register(definition(ToolSideEffect.WRITE, "{}", ToolDefinition.ToolPolicy.defaults()));

        ToolResult result = executor.invoke(readContext(), TOOL, objectNode("{}"));

        assertThat(result.errorCode()).isEqualTo("CONFIRMATION_REQUIRED");
        verify(writeExecutionTemplate, never()).execute(any(), anyString(), any(), any());
    }

    /** 已确认但缺少幂等键时仍必须拒绝，防止网络重试重复写入。 */
    @Test
    void approvedWriteWithoutIdempotencyKeyIsRejected() throws Exception {
        properties.getTool().setWriteEnabled(true);
        register(definition(ToolSideEffect.WRITE, "{}", ToolDefinition.ToolPolicy.defaults()));

        ToolResult result = executor.invoke(
                new ToolContext("eval-user", "c1", true, ""), TOOL, objectNode("{}"));

        assertThat(result.errorCode()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
        verify(writeExecutionTemplate, never()).execute(any(), anyString(), any(), any());
    }

    /** Schema 必填参数缺失时在最终执行前拒绝。 */
    @Test
    void invalidArgumentsAreRejected() throws Exception {
        register(definition(ToolSideEffect.READ_ONLY,
                "{\"type\":\"object\",\"required\":[\"recordId\"],"
                        + "\"properties\":{\"recordId\":{\"type\":\"string\"}}}",
                ToolDefinition.ToolPolicy.defaults()));

        ToolResult result = executor.invoke(readContext(), TOOL, objectNode("{}"));

        assertThat(result.errorCode()).isEqualTo("INVALID_ARGS");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    /** 限流拒绝后不能继续占用线程池或执行工具。 */
    @Test
    void rateLimitedCallIsRejected() throws Exception {
        register(definition(ToolSideEffect.READ_ONLY, "{}", ToolDefinition.ToolPolicy.defaults()));
        when(rateLimiter.tryAcquire(anyString(), anyInt())).thenReturn(false);

        ToolResult result = executor.invoke(readContext(), TOOL, objectNode("{}"));

        assertThat(result.errorCode()).isEqualTo("RATE_LIMITED");
        verify(tool, never()).execute(any(), any(JsonNode.class));
    }

    /** 合法只读调用通过全部门禁后正常执行并返回工具结果。 */
    @Test
    void validReadOnlyCallExecutes() throws Exception {
        register(definition(ToolSideEffect.READ_ONLY, "{}", ToolDefinition.ToolPolicy.defaults()));

        ToolResult result = executor.invoke(readContext(), TOOL, objectNode("{}"));

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).isEqualTo("成功");
        verify(tool).execute(any(), any(JsonNode.class));
    }

    /** 已确认且带幂等键的写调用必须经过共享写执行模板。 */
    @Test
    void approvedWriteUsesSharedExecutionTemplate() throws Exception {
        properties.getTool().setWriteEnabled(true);
        register(definition(ToolSideEffect.WRITE, "{}", ToolDefinition.ToolPolicy.defaults()));
        ToolContext context = new ToolContext("eval-user", "c1", true, "cfm_1");
        when(writeExecutionTemplate.execute(any(), eq(TOOL), any(JsonNode.class), any()))
                .thenAnswer(invocation -> {
                    Callable<?> business = invocation.getArgument(3);
                    return business.call();
                });

        ToolResult result = executor.invoke(context, TOOL, objectNode("{}"));

        assertThat(result.success()).isTrue();
        verify(writeExecutionTemplate).execute(eq(context), eq(TOOL),
                any(JsonNode.class), any());
        verify(tool).execute(eq(context), any(JsonNode.class));
    }

    /** 注册一个稳定工具定义供执行器查找。 */
    private void register(ToolDefinition definition) {
        when(registry.findRegistered(TOOL))
                .thenReturn(Optional.of(new ToolRegistry.RegisteredTool(tool, definition)));
    }

    /** 构造工具定义；测试只关注门禁，不引入复杂业务参数。 */
    private ToolDefinition definition(ToolSideEffect sideEffect, String schema,
                                      ToolDefinition.ToolPolicy policy) {
        return new ToolDefinition(TOOL, "演示工具", schema, sideEffect, 1_000, policy);
    }

    /** 构造普通只读上下文。 */
    private ToolContext readContext() {
        return new ToolContext("eval-user", "c1");
    }

    /** 解析测试参数 JSON，输入固定且合法。 */
    private JsonNode objectNode(String json) {
        return JsonUtil.fromJson(json, JsonNode.class);
    }
}
