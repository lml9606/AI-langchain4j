package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 工具注册表单测：定义校验、启停与稳定定义读取。 */
class ToolRegistryTest {

    @Test
    @DisplayName("自动注册全部启用工具")
    void registersAllEnabledTools() {
        ToolRegistry registry = new ToolRegistry(
                List.of(tool("a", ToolSideEffect.READ_ONLY, ToolDefinition.ToolPolicy.defaults()),
                        tool("b", ToolSideEffect.WRITE, ToolDefinition.ToolPolicy.defaults())));

        assertThat(registry.size()).isEqualTo(2);
        assertThat(registry.definitions()).extracting(ToolDefinition::name)
                .containsExactlyInAnyOrder("a", "b");
    }

    @Test
    @DisplayName("同名工具 -> 启动期 fail-fast，避免执行歧义")
    void duplicateToolFailsFast() {
        assertThatThrownBy(() -> new ToolRegistry(List.of(
                tool("a", ToolSideEffect.READ_ONLY, ToolDefinition.ToolPolicy.defaults()),
                tool("a", ToolSideEffect.WRITE, ToolDefinition.ToolPolicy.defaults()))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    @Test
    @DisplayName("InputSchema 非法 -> 启动期 fail-fast")
    void invalidSchemaFailsFast() {
        AgentTool bad = new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition("bad", "坏工具", "not-json",
                        ToolSideEffect.READ_ONLY, 1000);
            }

            @Override
            public ToolResult execute(ToolContext context, JsonNode args) {
                return ToolResult.success(Map.of(), "ok", List.of(), 0);
            }
        };

        assertThatThrownBy(() -> new ToolRegistry(List.of(bad)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("inputSchema");
    }

    @Test
    @DisplayName("定义中 disabled=true -> 不进入注册表")
    void disabledToolIsNotRegistered() {
        ToolRegistry registry = new ToolRegistry(List.of(tool("off", ToolSideEffect.READ_ONLY,
                new ToolDefinition.ToolPolicy(false, 0, Set.of()))));

        assertThat(registry.size()).isZero();
        assertThat(registry.find("off")).isEmpty();
    }

    private AgentTool tool(String name, ToolSideEffect sideEffect, ToolDefinition.ToolPolicy policy) {
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
