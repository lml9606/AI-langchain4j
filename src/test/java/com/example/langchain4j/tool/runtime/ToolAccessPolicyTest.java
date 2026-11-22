package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.workflow.TaskWorkflow;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** 工具访问策略单测：角色、写开关、危险工具与整组预检。 */
class ToolAccessPolicyTest {

    @Test
    @DisplayName("角色匹配且写开关开启 -> ALLOWED")
    void allowReadTool() {
        ToolAccessPolicy policy = policy(properties(true), tool("read", ToolSideEffect.READ_ONLY,
                ToolDefinition.ToolPolicy.defaults()));

        assertThat(policy.check("read", "USER").status())
                .isEqualTo(ToolAccessPolicy.Status.ALLOWED);
    }

    @Test
    @DisplayName("工具存在但角色无权 -> PERMISSION_REQUIRED，而不是静默消失")
    void rejectByRole() {
        ToolAccessPolicy policy = policy(properties(true), tool("admin", ToolSideEffect.READ_ONLY,
                new ToolDefinition.ToolPolicy(true, 0, Set.of("ADMIN"))));

        ToolAccessPolicy.Decision decision = policy.check("admin", "USER");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.status()).isEqualTo(ToolAccessPolicy.Status.PERMISSION_REQUIRED);
    }

    @Test
    @DisplayName("写开关关闭 -> WRITE_DISABLED")
    void rejectWriteWhenDisabled() {
        ToolAccessPolicy policy = policy(properties(false), tool("write", ToolSideEffect.WRITE,
                ToolDefinition.ToolPolicy.defaults()));

        assertThat(policy.check("write", "USER").status())
                .isEqualTo(ToolAccessPolicy.Status.WRITE_DISABLED);
    }

    @Test
    @DisplayName("整组预检任一工具失败 -> 整个任务拒绝")
    void rejectWholeWorkflow() {
        ToolAccessPolicy policy = policy(properties(true),
                tool("read", ToolSideEffect.READ_ONLY, ToolDefinition.ToolPolicy.defaults()),
                tool("admin", ToolSideEffect.READ_ONLY,
                        new ToolDefinition.ToolPolicy(true, 0, Set.of("ADMIN"))));
        TaskWorkflow workflow = new TaskWorkflow() {
            @Override
            public String id() {
                return "wf";
            }

            @Override
            public String description() {
                return "测试";
            }

            @Override
            public List<Stage> stages() {
                return List.of(new Stage("main", "主阶段", List.of("read", "admin")));
            }
        };

        ToolAccessPolicy.Decision decision = policy.checkAll(workflow, "USER");

        assertThat(decision.allowed()).isFalse();
        assertThat(decision.toolName()).isEqualTo("admin");
    }

    @Test
    @DisplayName("已解析定义校验 -> 直接复用同一份定义；null 定义视为不存在")
    void checkResolvedDefinition() {
        ToolAccessPolicy policy = policy(properties(true));
        ToolDefinition definition = new ToolDefinition("read", "测试工具",
                "{\"type\":\"object\",\"properties\":{}}", ToolSideEffect.READ_ONLY, 1000);

        assertThat(policy.check(definition, "USER").status())
                .isEqualTo(ToolAccessPolicy.Status.ALLOWED);
        assertThat(policy.check((ToolDefinition) null, "USER").status())
                .isEqualTo(ToolAccessPolicy.Status.NOT_FOUND);
    }

    private AgentProperties properties(boolean writeEnabled) {
        AgentProperties properties = new AgentProperties();
        properties.getTool().setWriteEnabled(writeEnabled);
        return properties;
    }

    private ToolAccessPolicy policy(AgentProperties properties, AgentTool... tools) {
        return new ToolAccessPolicy(new ToolRegistry(List.of(tools)), properties);
    }

    private AgentTool tool(String name, ToolSideEffect sideEffect,
                           ToolDefinition.ToolPolicy policy) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "测试工具", "{\"type\":\"object\",\"properties\":{}}",
                        sideEffect, 1000, policy);
            }

            @Override
            public ToolResult execute(ToolContext context, JsonNode args) {
                return ToolResult.success(Map.of(), "ok", List.of(), 0);
            }
        };
    }
}
