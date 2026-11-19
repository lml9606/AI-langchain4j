package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.config.AgentProperties;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 工具注册表单测：白名单是"模型只能用指定工具"的第一道硬约束。
 */
class ToolRegistryTest {

    @Test
    @DisplayName("白名单非空 → 只有列出工具可见、可执行")
    void allowlistFiltersTools() {
        AgentProperties properties = new AgentProperties();
        properties.getTool().setEnabledTools(List.of("a"));

        ToolRegistry registry = new ToolRegistry(
                List.of(tool("a", ToolSideEffect.READ_ONLY), tool("b", ToolSideEffect.WRITE)), properties);

        assertThat(registry.definitions()).extracting(ToolDefinition::name).containsExactly("a");
        assertThat(registry.find("a")).isPresent();
        assertThat(registry.find("b")).isEmpty();
    }

    @Test
    @DisplayName("白名单为空 → 注册全部工具")
    void emptyAllowlistRegistersAll() {
        AgentProperties properties = new AgentProperties();

        ToolRegistry registry = new ToolRegistry(
                List.of(tool("a", ToolSideEffect.READ_ONLY), tool("b", ToolSideEffect.WRITE)), properties);

        assertThat(registry.size()).isEqualTo(2);
    }

    @Test
    @DisplayName("白名单写了不存在的工具名 → 启动期 fail-fast")
    void unknownEnabledToolFailsFast() {
        AgentProperties properties = new AgentProperties();
        properties.getTool().setEnabledTools(List.of("missing"));

        assertThatThrownBy(() -> new ToolRegistry(List.of(tool("a", ToolSideEffect.READ_ONLY)), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("missing");
    }

    @Test
    @DisplayName("同名工具 → 启动期 fail-fast，避免执行歧义")
    void duplicateToolFailsFast() {
        AgentProperties properties = new AgentProperties();

        assertThatThrownBy(() -> new ToolRegistry(
                List.of(tool("a", ToolSideEffect.READ_ONLY), tool("a", ToolSideEffect.WRITE)), properties))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate");
    }

    private AgentTool tool(String name, ToolSideEffect sideEffect) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "测试工具", "{}", List.of(), sideEffect, 1000);
            }

            @Override
            public ToolResult execute(ToolContext context, JsonNode args) {
                return ToolResult.success(Map.of(), "ok", List.of(), 0);
            }
        };
    }
}
