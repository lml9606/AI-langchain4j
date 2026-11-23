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
 *   <li>一次任务允许零到多个写操作，写操作上限由 agent.task.max-writes-per-task 控制；</li>
 *   <li>确认阶段 pending 中的每个写步骤都必须能在计划里按 stepNo 找到，工具和参数完全一致。</li>
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
            // 含 $ref 的参数在规划阶段还没有真实值，只能校验引用语法和方向；
            // 真正执行前会先解析引用，再由 ToolExecutor 按 Schema 做最终校验。
            if (!containsRef(argsNode)) {
                ToolInputValidator.Result schemaValidation = ToolInputValidator.validate(
                        definition, argsNode == null || argsNode.isNull() ? null : argsNode);
                if (!schemaValidation.valid()) {
                    return ParseResult.invalid("工具 " + expectedTool + " 参数不合法："
                            + schemaValidation.message());
                }
            }

            Map<String, Object> args = argsNode != null && argsNode.isObject()
                    ? JsonUtil.mapper().convertValue(argsNode,
                    new TypeReference<Map<String, Object>>() {
                    }) : Map.of();
            Map<String, Object> step = new LinkedHashMap<>();
            step.put("stepNo", index);
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

        List<Map<String, Object>> writeSteps = new ArrayList<>();
        ToolDefinition writeDefinition = null;
        ValidationResult refCheck = validateRefs(plan);
        if (!refCheck.valid()) {
            return new PlanValidationResult(refCheck, List.of(), null);
        }
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
                if (!containsRef(args)) {
                    ToolInputValidator.Result schemaValidation =
                            ToolInputValidator.validate(definition, args);
                    if (!schemaValidation.valid()) {
                        return PlanValidationResult.invalid("ARGS_INVALID",
                                "工具 " + toolName + " 参数不合法：" + schemaValidation.message());
                    }
                }
                if (definition.sideEffect() != ToolSideEffect.READ_ONLY) {
                    writeSteps.add(step);
                    if (writeDefinition == null) {
                        writeDefinition = definition;
                    }
                }
            }
        }

        if (phase == TaskPlanPhase.CONFIRM) {
            if (writeSteps.isEmpty()) {
                return PlanValidationResult.invalid("WRITE_MISSING",
                        "执行计划中没有待确认的写操作");
            }
            ValidationResult pendingCheck = validatePending(pendingAction, writeSteps);
            if (!pendingCheck.valid()) {
                return new PlanValidationResult(pendingCheck, writeSteps, writeDefinition);
            }
        }

        ValidationResult extension = validateExtensions(phase, workflow, plan,
                definitions, pendingAction);
        if (!extension.valid()) {
            return new PlanValidationResult(extension, writeSteps, writeDefinition);
        }
        return new PlanValidationResult(ValidationResult.ok(), writeSteps, writeDefinition);
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
                                             List<Map<String, Object>> writeSteps) {
        if (pendingAction == null || pendingAction.isEmpty()) {
            return ValidationResult.invalid("PENDING_MISSING", "确认请求缺少待执行动作");
        }
        Object rawSteps = pendingAction.get("steps");
        if (rawSteps instanceof List<?> pendingSteps) {
            if (pendingSteps.size() != writeSteps.size()) {
                return ValidationResult.invalid("PENDING_COUNT_MISMATCH",
                        "待确认写步骤数量与执行计划不一致");
            }
            for (int index = 0; index < pendingSteps.size(); index++) {
                if (!(pendingSteps.get(index) instanceof Map<?, ?> pending)) {
                    return ValidationResult.invalid("PENDING_STEP_INVALID", "待确认步骤格式错误");
                }
                int stepNo = pending.get("stepNo") instanceof Number number
                        ? number.intValue() : index;
                // stepNo 是计划中的绝对步骤号（前面可以有只读步骤），不能用 writeSteps.size()
                // 当上界；正确做法是按 stepNo 精确找到对应的写步骤。
                Map<String, Object> expected = writeSteps.stream()
                        .filter(candidate -> pending.get("stepNo") instanceof Number number
                                && number.intValue() == intOf(candidate.get("stepNo")))
                        .findFirst()
                        .orElse(null);
                if (expected == null) {
                    return ValidationResult.invalid("PENDING_STEP_INVALID",
                            "待确认步骤不在计划的写步骤中：" + stepNo);
                }
                ValidationResult single = validatePendingStep(pending, expected);
                if (!single.valid()) {
                    return single;
                }
            }
            return ValidationResult.ok();
        }
        // 兼容旧版单写确认卡片：只有 tool/args 两个字段
        if (writeSteps.size() != 1) {
            return ValidationResult.invalid("PENDING_COUNT_MISMATCH",
                    "待确认动作只有单个工具，与多步骤计划不一致");
        }
        return validatePendingStep(pendingAction, writeSteps.get(0));
    }

    private ValidationResult validatePendingStep(Map<?, ?> pending, Map<String, Object> writeStep) {
        String pendingTool = String.valueOf(pending.get("tool") == null
                ? "" : pending.get("tool")).trim();
        String planTool = String.valueOf(writeStep.get("tool") == null
                ? "" : writeStep.get("tool")).trim();
        if (!pendingTool.equals(planTool)) {
            return ValidationResult.invalid("PENDING_TOOL_MISMATCH",
                    "待确认工具与执行计划不一致");
        }
        JsonNode planArgs = JsonUtil.mapper().valueToTree(
                writeStep.get("args") == null ? Map.of() : writeStep.get("args"));
        JsonNode pendingArgs = JsonUtil.mapper().valueToTree(
                pending.get("args") == null ? Map.of() : pending.get("args"));
        if (!planArgs.equals(pendingArgs)) {
            return ValidationResult.invalid("PENDING_ARGS_MISMATCH",
                    "待确认参数与执行计划不一致");
        }
        return ValidationResult.ok();
    }

    /** 引用只能指向更早的步骤，避免循环引用和读未执行结果。 */
    private ValidationResult validateRefs(List<Map<String, Object>> plan) {
        if (plan == null) {
            return ValidationResult.ok();
        }
        for (int index = 0; index < plan.size(); index++) {
            Map<String, Object> step = plan.get(index);
            if (step == null) {
                return ValidationResult.invalid("STEP_INVALID", "执行计划包含无效步骤");
            }
            JsonNode args = JsonUtil.mapper().valueToTree(
                    step.get("args") == null ? Map.of() : step.get("args"));
            String invalid = firstForwardRef(args, index);
            if (invalid != null) {
                return ValidationResult.invalid("REF_INVALID",
                        "步骤 " + (index + 1) + " 引用了不允许的步骤: " + invalid);
            }
        }
        return ValidationResult.ok();
    }

    private String firstForwardRef(JsonNode node, int currentStep) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            if (node.size() == 1 && node.has("$ref")) {
                return checkRef(node.get("$ref").asText(""), currentStep);
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                String invalid = firstForwardRef(fields.next().getValue(), currentStep);
                if (invalid != null) {
                    return invalid;
                }
            }
            return null;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                String invalid = firstForwardRef(item, currentStep);
                if (invalid != null) {
                    return invalid;
                }
            }
            return null;
        }
        if (node.isTextual() && node.asText().startsWith("$ref:")) {
            return checkRef(node.asText().substring("$ref:".length()), currentStep);
        }
        return null;
    }

    private String checkRef(String rawRef, int currentStep) {
        String ref = rawRef == null ? "" : rawRef.trim();
        if (ref.startsWith("step:")) {
            ref = ref.substring("step:".length());
        }
        int separator = ref.indexOf('.');
        int hash = ref.indexOf('#');
        if (separator < 0 || (hash >= 0 && hash < separator)) {
            separator = hash;
        }
        String stepPart = separator < 0 ? ref : ref.substring(0, separator);
        int stepNo;
        try {
            stepNo = Integer.parseInt(stepPart.trim());
        } catch (NumberFormatException e) {
            return rawRef + "（步骤号非法）";
        }
        if (stepNo < 0 || stepNo >= currentStep) {
            return rawRef + "（只能引用更早步骤）";
        }
        return null;
    }

    private int intOf(Object value) {
        return value instanceof Number number ? number.intValue() : -1;
    }

    private boolean containsRef(JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isObject()) {
            if (node.size() == 1 && node.has("$ref")) {
                return true;
            }
            var fields = node.fields();
            while (fields.hasNext()) {
                if (containsRef(fields.next().getValue())) {
                    return true;
                }
            }
            return false;
        }
        if (node.isArray()) {
            for (JsonNode item : node) {
                if (containsRef(item)) {
                    return true;
                }
            }
            return false;
        }
        return node.isTextual() && node.asText().startsWith("$ref:");
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

    /** 结构化计划复核结果；writeSteps 是全部待确认写步骤。 */
    public record PlanValidationResult(ValidationResult validation,
                                       List<Map<String, Object>> writeSteps,
                                       ToolDefinition writeDefinition) {

        public PlanValidationResult {
            validation = validation == null ? ValidationResult.ok() : validation;
            writeSteps = writeSteps == null ? List.of() : List.copyOf(writeSteps);
        }

        public boolean valid() {
            return validation.valid();
        }

        public String message() {
            return validation.message();
        }

        public static PlanValidationResult invalid(String code, String message) {
            return new PlanValidationResult(ValidationResult.invalid(code, message), List.of(), null);
        }

        /** 兼容旧调用：返回第一个写步骤。 */
        public Map<String, Object> writeStep() {
            return writeSteps.isEmpty() ? null : writeSteps.get(0);
        }
    }
}
