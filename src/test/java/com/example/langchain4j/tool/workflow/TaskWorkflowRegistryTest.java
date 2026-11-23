package com.example.langchain4j.tool.workflow;

import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 任务契约注册表单测：启动期校验与计划精确匹配。 */
class TaskWorkflowRegistryTest {

    @Test
    @DisplayName("合法工作流注册成功，并可按契约顺序读取工具定义")
    void registerValidWorkflow() {
        ToolRegistry tools = new ToolRegistry(List.of(
                tool("first", ToolSideEffect.READ_ONLY),
                tool("second", ToolSideEffect.READ_ONLY)));
        TaskWorkflow workflow = workflow("wf", "stage", "first", "second");
        TaskWorkflowRegistry registry = new TaskWorkflowRegistry(List.of(workflow), tools);

        assertThat(registry.all()).containsExactly(workflow);
        assertThat(registry.definitions(workflow)).extracting(ToolDefinition::name)
                .containsExactly("first", "second");
    }

    @Test
    @DisplayName("引用未注册工具 -> 启动期 fail-fast")
    void rejectUnknownTool() {
        ToolRegistry tools = new ToolRegistry(List.of(tool("first", ToolSideEffect.READ_ONLY)));

        assertThatThrownBy(() -> new TaskWorkflowRegistry(
                List.of(workflow("wf", "stage", "missing")), tools))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unknown or disabled tool");
    }

    @Test
    @DisplayName("引用危险工具 -> 启动期 fail-fast")
    void rejectDangerousTool() {
        ToolRegistry tools = new ToolRegistry(List.of(tool("danger", ToolSideEffect.DANGEROUS)));

        assertThatThrownBy(() -> new TaskWorkflowRegistry(
                List.of(workflow("wf", "stage", "danger")), tools))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dangerous tool");
    }

    @Test
    @DisplayName("同一工具可在多个步骤重复 -> 允许")
    void allowDuplicateTool() {
        ToolRegistry tools = new ToolRegistry(List.of(tool("first", ToolSideEffect.READ_ONLY)));

        TaskWorkflowRegistry registry = new TaskWorkflowRegistry(
                List.of(workflow("wf", "stage", "first", "first")), tools);

        assertThat(registry.find("wf")).isPresent();
        assertThat(registry.validatePlan(registry.find("wf").orElseThrow(),
                List.of(Map.of("tool", "first"), Map.of("tool", "first"))).valid()).isTrue();
    }

    @Test
    @DisplayName("计划必须与固定工具序列完全一致")
    void validateExactPlan() {
        ToolRegistry tools = new ToolRegistry(List.of(
                tool("first", ToolSideEffect.READ_ONLY),
                tool("second", ToolSideEffect.READ_ONLY)));
        TaskWorkflow workflow = workflow("wf", "stage", "first", "second");
        TaskWorkflowRegistry registry = new TaskWorkflowRegistry(List.of(workflow), tools);

        assertThat(registry.validatePlan(workflow, List.of(
                Map.of("tool", "first"), Map.of("tool", "second"))).valid()).isTrue();
        assertThat(registry.validatePlan(workflow, List.of(Map.of("tool", "first"))).valid())
                .isFalse();
        assertThat(registry.validatePlan(workflow, List.of(
                Map.of("tool", "second"), Map.of("tool", "first"))).valid()).isFalse();
    }

    @Test
    @DisplayName("确认计划可从首步恢复 workflowId")
    void readWorkflowIdFromPlan() {
        ToolRegistry tools = new ToolRegistry(List.of(tool("first", ToolSideEffect.READ_ONLY)));
        TaskWorkflowRegistry registry = new TaskWorkflowRegistry(
                List.of(workflow("wf", "stage", "first")), tools);

        assertThat(registry.workflowIdOfPlan(List.of(
                Map.of("workflowId", "wf", "tool", "first")))).contains("wf");
        assertThat(registry.workflowIdOfPlan(List.of())).isEmpty();
    }

    private TaskWorkflow workflow(String id, String stage, String... tools) {
        return new TaskWorkflow() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return "测试工作流";
            }

            @Override
            public List<Stage> stages() {
                return List.of(new Stage(stage, "测试阶段", List.of(tools)));
            }
        };
    }

    private AgentTool tool(String name, ToolSideEffect sideEffect) {
        return new AgentTool() {
            @Override
            public ToolDefinition definition() {
                return new ToolDefinition(name, "测试工具", "{\"type\":\"object\",\"properties\":{}}",
                        sideEffect, 1000);
            }

            @Override
            public ToolResult execute(ToolContext context, JsonNode args) {
                return ToolResult.success(Map.of(), "ok", List.of(), 0);
            }
        };
    }
}
