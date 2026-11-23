package com.example.langchain4j.tool.workflow;

import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ToolRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 任务工作流注册表：启动期固化业务任务与工具的绑定关系。
 *
 * 工作流引用不存在的工具、引用危险工具、阶段或工具重复，都会在启动期失败，
 * 避免线上出现“计划能生成、执行时才发现工具不可用”的半成品链路。
 */
@Component
@Slf4j
public class TaskWorkflowRegistry {

    private static final Pattern ID_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");

    private final Map<String, TaskWorkflow> workflows;
    private final ToolRegistry toolRegistry;

    public TaskWorkflowRegistry(List<TaskWorkflow> discovered, ToolRegistry toolRegistry) {
        this.toolRegistry = toolRegistry;
        Map<String, TaskWorkflow> registered = new LinkedHashMap<>();
        if (discovered != null) {
            for (TaskWorkflow workflow : discovered) {
                validate(workflow);
                if (registered.putIfAbsent(workflow.id(), workflow) != null) {
                    throw new IllegalStateException("[workflow] duplicate workflow id: " + workflow.id());
                }
            }
        }
        this.workflows = Collections.unmodifiableMap(registered);
        log.info("[workflow] registered workflows: {}", this.workflows.keySet());
    }

    /** 返回全部工作流，按 ID 排序保证模型目录与日志稳定。 */
    public List<TaskWorkflow> all() {
        return workflows.values().stream()
                .sorted((left, right) -> left.id().compareTo(right.id()))
                .toList();
    }

    public Optional<TaskWorkflow> find(String workflowId) {
        return Optional.ofNullable(workflows.get(workflowId));
    }

    /** 按工作流契约顺序返回工具定义；注册期已校验存在性，这里属于防御性兜底。 */
    public List<ToolDefinition> definitions(TaskWorkflow workflow) {
        return workflow.requiredTools().stream()
                .map(toolName -> toolRegistry.definition(toolName)
                        .orElseThrow(() -> new IllegalStateException(
                                "[workflow] missing tool definition: " + toolName)))
                .toList();
    }

    /**
     * 校验计划是否严格符合契约：步骤数量、工具名和顺序都必须完全一致。
     */
    public Validation validatePlan(TaskWorkflow workflow, List<Map<String, Object>> plan) {
        List<String> expected = workflow.requiredTools();
        if (plan == null || plan.size() != expected.size()) {
            return Validation.invalid("任务计划与固定工具契约不一致：应包含 " + expected.size()
                    + " 步，实际 " + (plan == null ? 0 : plan.size()) + " 步");
        }
        for (int index = 0; index < expected.size(); index++) {
            Object actual = plan.get(index).get("tool");
            if (!expected.get(index).equals(String.valueOf(actual))) {
                return Validation.invalid("任务计划与固定工具契约不一致：第 " + (index + 1)
                        + " 步应为 " + expected.get(index) + "，实际为 " + actual);
            }
        }
        return Validation.ok();
    }

    /** 计划中的第一个步骤携带 workflowId，确认链路回放时可据此恢复契约。 */
    public Optional<String> workflowIdOfPlan(List<Map<String, Object>> plan) {
        if (plan == null || plan.isEmpty()) {
            return Optional.empty();
        }
        Object value = plan.get(0).get("workflowId");
        String workflowId = value == null ? "" : String.valueOf(value).trim();
        return workflowId.isBlank() ? Optional.empty() : Optional.of(workflowId);
    }

    private void validate(TaskWorkflow workflow) {
        if (workflow == null) {
            throw new IllegalStateException("[workflow] TaskWorkflow bean must not be null");
        }
        String workflowId = workflow.id();
        if (workflowId == null || !ID_PATTERN.matcher(workflowId).matches()) {
            throw new IllegalStateException("[workflow] invalid workflow id: " + workflowId);
        }
        if (workflow.description().isBlank()) {
            throw new IllegalStateException("[workflow] description must not be blank: " + workflow.id());
        }
        if (workflow.stages() == null || workflow.stages().isEmpty()) {
            throw new IllegalStateException("[workflow] at least one stage is required: " + workflow.id());
        }

        Set<String> stageIds = new LinkedHashSet<>();
        for (TaskWorkflow.Stage stage : workflow.stages()) {
            if (stage == null || stage.id().isBlank()) {
                throw new IllegalStateException("[workflow] stage id must not be blank: " + workflow.id());
            }
            if (!stageIds.add(stage.id())) {
                throw new IllegalStateException("[workflow] duplicate stage id: "
                        + workflow.id() + "/" + stage.id());
            }
            if (stage.tools().isEmpty()) {
                throw new IllegalStateException("[workflow] stage must declare tools: "
                        + workflow.id() + "/" + stage.id());
            }
            for (String toolName : stage.tools()) {
                // 同一个工具允许在多个阶段重复出现（例如查询 a/b/c 都走 query 工具），
                // 契约校验按"工具序列 + 参数"进行，不按工具名去重。
                ToolDefinition definition = toolRegistry.definition(toolName)
                        .orElseThrow(() -> new IllegalStateException(
                                "[workflow] unknown or disabled tool: "
                                        + workflow.id() + "/" + toolName));
                if (definition.sideEffect() == ToolSideEffect.DANGEROUS) {
                    throw new IllegalStateException("[workflow] dangerous tool is forbidden: "
                            + workflow.id() + "/" + toolName);
                }
            }
        }
    }

    /** 计划契约校验结果。 */
    public record Validation(boolean valid, String message) {

        public static Validation ok() {
            return new Validation(true, "");
        }

        public static Validation invalid(String message) {
            return new Validation(false, message);
        }
    }
}
