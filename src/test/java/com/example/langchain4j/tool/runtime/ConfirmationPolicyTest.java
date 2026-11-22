package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 确认资格策略单测：确认节点只依赖策略结论，不重复维护工具规则。 */
@ExtendWith(MockitoExtension.class)
class ConfirmationPolicyTest {

    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private ToolAccessPolicy accessPolicy;
    @Mock
    private AgentTool agentTool;

    private AgentProperties properties;
    private ConfirmationPolicy policy;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getTool().setWriteEnabled(true);
        policy = new ConfirmationPolicy(toolRegistry, accessPolicy, properties);
    }

    private ToolDefinition definition(String name, ToolSideEffect sideEffect) {
        return new ToolDefinition(name, "测试工具", "{\"type\":\"object\"}", sideEffect, 3000);
    }

    private void registered(ToolDefinition definition) {
        when(toolRegistry.findRegistered(definition.name())).thenReturn(Optional.of(
                new ToolRegistry.RegisteredTool(agentTool, definition)));
    }

    @Test
    @DisplayName("空工具名 -> 缺少待执行工具")
    void rejectMissingTool() {
        ConfirmationPolicy.Decision decision = policy.check(" ", "USER");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.status()).isEqualTo(ConfirmationPolicy.Status.MISSING_TOOL);
        assertThat(decision.message()).contains("缺少待执行工具");
        verify(toolRegistry, never()).findRegistered(anyString());
    }

    @Test
    @DisplayName("未知工具 -> 不进入确认流程")
    void rejectUnknownTool() {
        when(toolRegistry.findRegistered("write_tool")).thenReturn(Optional.empty());

        ConfirmationPolicy.Decision decision = policy.check("write_tool", "USER");

        assertThat(decision.status()).isEqualTo(ConfirmationPolicy.Status.UNKNOWN_TOOL);
        assertThat(decision.message()).contains("不存在或已停用");
    }

    @Test
    @DisplayName("只读工具 -> 不生成人工确认")
    void rejectReadOnlyTool() {
        registered(definition("read_tool", ToolSideEffect.READ_ONLY));

        ConfirmationPolicy.Decision decision = policy.check("read_tool", "USER");

        assertThat(decision.status()).isEqualTo(ConfirmationPolicy.Status.NOT_WRITABLE);
        assertThat(decision.message()).contains("只读工具不需要人工确认");
    }

    @Test
    @DisplayName("全局写开关关闭 -> 不创建确认请求")
    void rejectWhenWriteDisabled() {
        properties.getTool().setWriteEnabled(false);

        ConfirmationPolicy.Decision decision = policy.check("write_tool", "USER");

        assertThat(decision.status()).isEqualTo(ConfirmationPolicy.Status.WRITE_DISABLED);
        assertThat(decision.message()).contains("已禁用");
        verify(toolRegistry, never()).findRegistered(anyString());
    }

    @Test
    @DisplayName("角色无权限 -> 返回策略层拒绝原因")
    void rejectWhenAccessDenied() {
        ToolDefinition definition = definition("write_tool", ToolSideEffect.WRITE);
        registered(definition);
        when(accessPolicy.check(eq(definition), anyString())).thenReturn(
                new ToolAccessPolicy.Decision(false,
                        ToolAccessPolicy.Status.PERMISSION_REQUIRED, "write_tool",
                        "当前角色无权执行该任务"));

        ConfirmationPolicy.Decision decision = policy.check("write_tool", "USER");

        assertThat(decision.status()).isEqualTo(ConfirmationPolicy.Status.FORBIDDEN_TOOL);
        assertThat(decision.message()).contains("无权");
    }

    @Test
    @DisplayName("可写且有权限 -> 返回稳定工具定义")
    void allowWriteTool() {
        ToolDefinition definition = definition("write_tool", ToolSideEffect.WRITE);
        registered(definition);
        when(accessPolicy.check(eq(definition), any())).thenReturn(
                new ToolAccessPolicy.Decision(true, ToolAccessPolicy.Status.ALLOWED,
                        "write_tool", ""));

        ConfirmationPolicy.Decision decision = policy.check("write_tool", "USER");

        assertThat(decision.allowed()).isTrue();
        assertThat(decision.status()).isEqualTo(ConfirmationPolicy.Status.ALLOWED);
        assertThat(decision.definition()).isEqualTo(definition);
    }
}
