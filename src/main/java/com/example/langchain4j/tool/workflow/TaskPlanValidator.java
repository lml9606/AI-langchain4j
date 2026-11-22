package com.example.langchain4j.tool.workflow;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ToolInputValidator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务计划校验器：把规划、确认、执行三个阶段共用的安全校验收口到一处。
 *
 * <p>负责四类内置规则：
 * <ol>
 *   <li>固定工作流契约：步骤数量、工具名、顺序不能增删换乱序；</li>
 *   <li>每个工具的参数必须符合启动期固化的 JSON Schema；</li>
 *   <li>一次任务最多一个写操作，确认阶段必须恰好有一个写操作；</li>
 *   <li>确认阶段 pending 工具和参数必须与计划中的写步骤完全一致。</li>
 * </ol>
 *
 * <p>业务扩展通过 {@link TaskPlanRule} 追加拒绝条件，内置规则不会被扩展规则绕过。
 */
@Component
public class TaskPlanValidator {

    private final TaskWorkflowRegistry workflowRegistry;
    private final List<TaskPlanRule> rules;

    public TaskPlanValidator(TaskWorkflowRegistry workflowRegistry, List<TaskPlanRule> rules) {
        this.workflowRegistry = workflowRegistry;
        this.rules = rules == null ? List.of() : List.copyOf(rules);
    }

    /**
     * 解析模型输出的 steps，并校验固定工作流契约和参数 Schema。
     *
     * <p>该入口只负责把原始 JSON 转成内部计划结构；写操作数量和 pending 一致性由
     * {@link #validate(TaskPlanPhase, TaskWorkflow, List, Map, Map)} 统一处理。
     */
    public ParseResult parseSteps(JsonNode stepsNode, TaskWorkflow workflow,
                                  Map<String, ToolDefinition> definitions,
                                  Map<String, String> stageByTool) {
        if (stepsNode == null || !stepsNode.isArray()) {
            return ParseResult.invalid("任务计划格式错误：steps 必须是数组");
        }
        List<String> expectedTools = workflow.requiredTools();
        if (stepsNode.size() != expectedTools.size()) {
            return ParseResult.invalid("任务计划与固定工具契约不一致：应包含 "
                    + expectedTools.size() + " 步，实际 " + stepsNode.size() + " 步");
        }

        List<Map<String, Object>> plan = new ArrayList<>(expectedTools.size());
        for (int index = 0; index < expectedTools.size(); index++) {
            JsonNode stepNode = stepsNode.get(index);
            String expectedTool = expectedTools.get(index);
            String actualTool = stepNode.path("tool").asText("").trim();
            if (!expectedTool.equals(actualTool)) {
                return ParseResult.invalid("任务计划与固定工具契约不一致：第 " + (index + 1)
                        + " 步应为 " + expectedTool + "，实际为 " + actualTool);
            }

            ToolDefinition definition = definitions == null ? null : definitions.get(expectedTool);
            if (definition == null) {
                return ParseResult.invalid("执行计划包含不存在或已停用的工具: " + expectedTool);
            }
            JsonNode argsNode = stepNode.get("args");
            if (argsNode != null && !argsNode.isNull() && !argsNode.isObject()) {
                return ParseResult.invalid("工具 " + expectedTool + " 的参数必须是 JSON 对象");
            }
            ToolInputValidator.Result schemaValidation = ToolInputValidator.validate(
                    definition, argsNode == null || argsNode.isNull() ? null : argsNode);
            if (!schemaValidation.valid()) {
                return ParseResult.invalid("工具 " + expectedTool + " 参数不合法："
                        + schemaValidation.message());
            }

            Map<String, Object> args = argsNode != null && argsNode.isObject()
                    ? JsonUtil.mapper().convertValue(argsNode,
                    new TypeReference<Map<String, Object>>() {
                    }) : Map.of();
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("workflowId", workflow.id());
            step.put("stage", stageByTool == null ? null : stageByTool.get(expectedTool));
            step.put("tool", expectedTool);
            step.put("args", args);
            step.put("purpose", stepNode.path("purpose").asText(""));
            step.put("sideEffect", definition.sideEffect().name());
            plan.add(step);
        }
        return ParseResult.ok(plan);
    }

    /**
     * 复核一份已经结构化的计划，并执行可插拔业务规则。
     *
     * <p>调用顺序固定为：工作流契约 → 参数 Schema → 写操作数量 → 确认阶段 pending 一致性 →
     * 扩展规则。任一步失败立即返回，后续规则不会执行。
     */
    public PlanValidationResult validate(TaskPlanPhase phase,
                                         TaskWorkflow workflow,
                                         List<Map<String, Object>> plan,
                                         Map<String, ToolDefinition> definitions,
                                         Map<String, Object> pendingAction) {
        if (workflow == null) {
            return PlanValidationResult.invalid("WORKFLOW_MISSING", "任务计划缺少固定工作流契约");
        }
        TaskWorkflowRegistry.Validation contract = workflowRegistry.validatePlan(workflow, plan);
        if (!contract.valid()) {
            return PlanValidationResult.invalid("CONTRACT_MISMATCH", contract.message());
        }

        Map<String, Object> writeStep = null;
        ToolDefinition writeDefinition = null;
        if (plan != null) {
            for (Map<String, Object> step : plan) {
                if (step == null) {
                    return PlanValidationResult.invalid("STEP_INVALID", "执行计划包含无效步骤");
                }
                String toolName = String.valueOf(step.get("tool") == null
                        ? "" : step.get("tool")).trim();
                if (toolName.isBlank()) {
                    return PlanValidationResult.invalid("STEP_TOOL_MISSING", "执行计划缺少工具名");
                }
                ToolDefinition definition = definitions == null ? null : definitions.get(toolName);
                if (definition == null) {
                    return PlanValidationResult.invalid("TOOL_UNKNOWN",
                            "执行计划包含不存在或已停用的工具: " + toolName);
                }
                JsonNode args = JsonUtil.mapper().valueToTree(
                        step.get("args") == null ? Map.of() : step.get("args"));
                ToolInputValidator.Result schemaValidation =
                        ToolInputValidator.validate(definition, args);
                if (!schemaValidation.valid()) {
                    return PlanValidationResult.invalid("ARGS_INVALID",
                            "工具 " + toolName + " 参数不合法：" + schemaValidation.message());
                }
                if (definition.sideEffect() != ToolSideEffect.READ_ONLY) {
                    if (writeStep != null) {
                        return PlanValidationResult.invalid("MULTIPLE_WRITES",
                                "一次只能确认一个写操作，请拆分为多轮处理");
                    }
                    writeStep = step;
                    writeDefinition = definition;
                }
            }
        }

        if (phase == TaskPlanPhase.CONFIRM) {
            if (writeStep == null) {
                return PlanValidationResult.invalid("WRITE_MISSING",
                        "执行计划中没有待确认的写操作");
            }
            ValidationResult pendingCheck = validatePending(pendingAction, writeStep);
            if (!pendingCheck.valid()) {
                return new PlanValidationResult(pendingCheck, writeStep, writeDefinition);
            }
        }

        ValidationResult extension = validateExtensions(phase, workflow, plan,
                definitions, pendingAction);
        if (!extension.valid()) {
            return new PlanValidationResult(extension, writeStep, writeDefinition);
        }
        return new PlanValidationResult(ValidationResult.ok(), writeStep, writeDefinition);
    }

    /**
     * 确认阶段入口：节点只传计划和待确认动作，由校验器自己解析 workflow 和工具定义。
     * <p>避免确认节点重复展开工作流目录，也保证确认阶段与规划阶段使用同一套契约来源。
     */
    public PlanValidationResult validateConfirm(List<Map<String, Object>> plan,
                                                Map<String, Object> pendingAction) {
        if (plan == null || plan.isEmpty()) {
            return PlanValidationResult.invalid("PLAN_MISSING", "确认请求缺少执行计划");
        }
        String workflowId = workflowRegistry.workflowIdOfPlan(plan).orElse("");
        TaskWorkflow workflow = workflowId.isBlank() ? null
                : workflowRegistry.find(workflowId).orElse(null);
        if (workflow == null) {
            return PlanValidationResult.invalid("WORKFLOW_MISSING",
                    "确认请求缺少有效的任务工作流契约");
        }
        return validate(TaskPlanPhase.CONFIRM, workflow, plan, definitionsOf(workflow),
                pendingAction);
    }

    /** 工作流工具定义转成按名称索引的只读快照；启动期已校验非空。 */
    private Map<String, ToolDefinition> definitionsOf(TaskWorkflow workflow) {
        Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
        List<ToolDefinition> tools = workflowRegistry.definitions(workflow);
        if (tools != null) {
            for (ToolDefinition definition : tools) {
                definitions.put(definition.name(), definition);
            }
        }
        return definitions;
    }

    private ValidationResult validatePending(Map<String, Object> pendingAction,
                                             Map<String, Object> writeStep) {
        if (pendingAction == null || pendingAction.isEmpty()) {
            return ValidationResult.invalid("PENDING_MISSING", "确认请求缺少待执行动作");
        }
        String pendingTool = String.valueOf(pendingAction.get("tool") == null
                ? "" : pendingAction.get("tool")).trim();
        String planTool = String.valueOf(writeStep.get("tool") == null
                ? "" : writeStep.get("tool")).trim();
        if (!pendingTool.equals(planTool)) {
            return ValidationResult.invalid("PENDING_TOOL_MISMATCH",
                    "待确认工具与执行计划不一致");
        }
        JsonNode planArgs = JsonUtil.mapper().valueToTree(
                writeStep.get("args") == null ? Map.of() : writeStep.get("args"));
        JsonNode pendingArgs = JsonUtil.mapper().valueToTree(
                pendingAction.get("args") == null ? Map.of() : pendingAction.get("args"));
        if (!planArgs.equals(pendingArgs)) {
            return ValidationResult.invalid("PENDING_ARGS_MISMATCH",
                    "待确认参数与执行计划不一致");
        }
        return ValidationResult.ok();
    }

    private ValidationResult validateExtensions(TaskPlanPhase phase, TaskWorkflow workflow,
                                                List<Map<String, Object>> plan,
                                                Map<String, ToolDefinition> definitions,
                                                Map<String, Object> pendingAction) {
        if (rules.isEmpty()) {
            return ValidationResult.ok();
        }
        TaskPlanContext context = new TaskPlanContext(phase, workflow, plan,
                definitions, pendingAction);
        for (TaskPlanRule rule : rules) {
            ValidationResult result;
            try {
                result = rule.validate(context);
            } catch (RuntimeException e) {
                return ValidationResult.invalid("EXTENSION_RULE_ERROR",
                        "任务计划扩展校验执行失败，已拒绝执行");
            }
            if (result == null || !result.valid()) {
                return result == null
                        ? ValidationResult.invalid("EXTENSION_RULE_EMPTY", "任务计划扩展校验未返回结论")
                        : result;
            }
        }
        return ValidationResult.ok();
    }

    /** 模型 steps 解析结果。 */
    public record ParseResult(ValidationResult validation,
                              List<Map<String, Object>> plan) {

        public ParseResult {
            validation = validation == null ? ValidationResult.ok() : validation;
            plan = plan == null ? List.of() : List.copyOf(plan);
        }

        public boolean valid() {
            return validation.valid();
        }

        public String message() {
            return validation.message();
        }

        public static ParseResult ok(List<Map<String, Object>> plan) {
            return new ParseResult(ValidationResult.ok(), plan);
        }

        public static ParseResult invalid(String message) {
            return new ParseResult(ValidationResult.invalid("PARSE_FAILED", message), List.of());
        }
    }

    /** 结构化计划复核结果；writeStep/writeDefinition 便于调用方生成确认卡片。 */
    public record PlanValidationResult(ValidationResult validation,
                                       Map<String, Object> writeStep,
                                       ToolDefinition writeDefinition) {

        public PlanValidationResult {
            validation = validation == null ? ValidationResult.ok() : validation;
        }

        public boolean valid() {
            return validation.valid();
        }

        public String message() {
            return validation.message();
        }

        public static PlanValidationResult invalid(String code, String message) {
            return new PlanValidationResult(ValidationResult.invalid(code, message), null, null);
        }
    }
}
