package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ToolAccessPolicy;
import com.example.langchain4j.tool.runtime.ToolInputValidator;
import com.example.langchain4j.tool.workflow.TaskWorkflow;
import com.example.langchain4j.tool.workflow.TaskWorkflowRegistry;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务规划节点：先选固定工作流，再在该工作流的工具契约内抽取实体并填写参数。
 *
 * <p>工具集合不再通过 TopK 召回交给模型自由选择。工作流在代码中声明完整工具集合与顺序，
 * 规划入口先整组校验权限，模型输出后再次校验“不增、不减、不换、不乱序”。
 */
@Slf4j
@Component
public class TaskPlanNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final TaskWorkflowRegistry workflowRegistry;
    private final ToolAccessPolicy accessPolicy;
    private final AgentProperties properties;

    public TaskPlanNode(LlmGateway llmGateway, PromptTemplateService promptTemplateService,
                        TaskWorkflowRegistry workflowRegistry, ToolAccessPolicy accessPolicy,
                        AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.workflowRegistry = workflowRegistry;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        int maxSteps = Math.max(1, properties.getTool().getMaxStepsPerTask());
        List<TaskWorkflow> workflows = workflowRegistry.all();
        if (workflows.isEmpty()) {
            log.warn("[task-plan] no workflow configured");
            return Map.of(WorkflowState.ERROR, "当前没有配置可用的任务工作流");
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        try {
            // 步骤1：模型只选任务类型，不接触任何工具名或参数，避免用 TopK 猜工具。
            String workflowPrompt = promptTemplateService.resolve(
                    "TASK_WORKFLOW_SELECT_SYSTEM", PromptTemplates.TASK_WORKFLOW_SELECT_SYSTEM);
            List<Map<String, Object>> workflowCatalog = workflowCatalog(workflows);
            String selectionPrompt = "用户请求：\n" + state.effectiveQuery()
                    + "\n\n任务目录：\n" + JsonUtil.toJson(workflowCatalog);
            log.info("[task-plan] selecting workflow, queryLength={}, workflows={}, model={}",
                    state.effectiveQuery().length(), workflowCatalog.size(), state.model());

            JsonNode selectedNode = llmGateway.generateJson(
                    LlmRequest.of(state.model(), workflowPrompt, selectionPrompt)
                            .scene("task-workflow-select"));
            if (selectedNode == null) {
                return error("任务类型识别失败：模型输出为空或非 JSON");
            }
            String workflowId = selectedNode.path("workflowId").asText("").trim();
            if (workflowId.isBlank()) {
                log.warn("[task-plan] no matching workflow, queryLength={}",
                        state.effectiveQuery().length());
                return error("无法确定要执行的任务类型，请明确说明要查询或修改什么");
            }
            TaskWorkflow workflow = workflowRegistry.find(workflowId).orElse(null);
            if (workflow == null) {
                log.warn("[task-plan] unknown workflow id={}, available={}",
                        workflowId, workflowCatalog.stream().map(item -> item.get("id")).toList());
                return error("任务类型不存在或不可用，请重新描述需求");
            }
            updates.put(WorkflowState.TASK_WORKFLOW_ID, workflow.id());

            // 步骤2：流程头节点整组预检 —— 任一工具无权限/停用/写开关关闭，整个任务拒绝。
            int requiredSteps = workflow.requiredTools().size();
            if (requiredSteps > maxSteps) {
                log.warn("[task-plan] workflow exceeds max steps, workflow={}, required={}, max={}",
                        workflow.id(), requiredSteps, maxSteps);
                return error("任务契约包含 " + requiredSteps + " 步，超过当前上限 " + maxSteps);
            }
            ToolAccessPolicy.Decision access = accessPolicy.checkAll(workflow, UserContext.role());
            if (!access.allowed()) {
                log.warn("[task-plan] workflow access denied, workflow={}, status={}, tool={}",
                        workflow.id(), access.status(), access.toolName());
                return error(access.message());
            }

            // 步骤3：只把该工作流的精确工具集合交给模型填参，不展示其他工作流工具。
            List<ToolDefinition> tools = workflowRegistry.definitions(workflow);
            Map<String, String> stageByTool = stageByTool(workflow);
            Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
            List<Map<String, Object>> catalog = new ArrayList<>();
            for (ToolDefinition definition : tools) {
                definitions.put(definition.name(), definition);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("stage", stageByTool.get(definition.name()));
                item.put("name", definition.name());
                item.put("description", definition.description());
                item.put("inputSchema", definition.inputSchema());
                item.put("sideEffect", definition.sideEffect().name());
                catalog.add(item);
            }

            String systemPrompt = promptTemplateService.resolveFormatted("TASK_PLAN_SYSTEM",
                    PromptTemplates.TASK_PLAN_SYSTEM, maxSteps);
            String userPrompt = "用户请求：\n" + state.effectiveQuery()
                    + "\n\n已选任务工作流：" + workflow.id()
                    + "（" + workflow.description() + "）"
                    + "\n\n固定工具序列（必须严格按此顺序）：\n" + JsonUtil.toJson(catalog);
            log.info("[task-plan] filling params, workflow={}, steps={}, names={}",
                    workflow.id(), catalog.size(), definitions.keySet());

            JsonNode node = llmGateway.generateJson(
                    LlmRequest.of(state.model(), systemPrompt, userPrompt).scene("task-plan"));
            if (node == null) {
                return error("任务计划生成失败：模型输出截断或非 JSON");
            }

            // 步骤4：解析实体与缺失槽位。
            Map<String, String> entities = new LinkedHashMap<>();
            JsonNode entitiesNode = node.get("entities");
            if (entitiesNode != null && entitiesNode.isObject()) {
                entitiesNode.fields().forEachRemaining(
                        entry -> entities.put(entry.getKey(), entry.getValue().asText("")));
            }
            List<String> missing = parseMissing(node.get("missing"));

            // 缺参优先：不执行工具，也不因故意不完整的步骤而报 Schema 错误。
            if (!missing.isEmpty()) {
                updates.put(WorkflowState.TASK_ENTITIES, entities);
                updates.put(WorkflowState.TASK_MISSING, missing);
                updates.put(WorkflowState.TASK_PLAN, List.of());
                updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
                updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
                updates.put(WorkflowState.TASK_CLARIFY, true);
                updates.put(WorkflowState.TASK_CONFIDENCE, 1.0);
                updates.put(WorkflowState.TASK_CONFIDENCE_REASON, "缺少必要参数，已生成追问");
                updates.put(WorkflowState.RESULT,
                        promptTemplateService.resolveFormatted("TASK_CLARIFY_ANSWER",
                                PromptTemplates.TASK_CLARIFY_ANSWER, slotLabels(missing)));
                updates.put(WorkflowState.ERROR, "");
                log.info("[task-plan] clarification required, workflow={}, missingSlots={}",
                        workflow.id(), missing);
                return updates;
            }

            // 步骤5：严格校验固定工具序列，多一个、少一个、换一个、顺序错都拒绝。
            JsonNode stepsNode = node.get("steps");
            if (stepsNode == null || !stepsNode.isArray()) {
                return error("任务计划格式错误：steps 必须是数组");
            }
            if (stepsNode.size() != requiredSteps) {
                log.warn("[task-plan] workflow contract mismatch, workflow={}, expected={}, actual={}",
                        workflow.id(), requiredSteps, stepsNode.size());
                return error("任务计划与固定工具契约不一致：应包含 " + requiredSteps
                        + " 步，实际 " + stepsNode.size() + " 步");
            }

            List<Map<String, Object>> plan = new ArrayList<>(requiredSteps);
            for (int index = 0; index < requiredSteps; index++) {
                JsonNode stepNode = stepsNode.get(index);
                String expectedTool = workflow.requiredTools().get(index);
                String actualTool = stepNode.path("tool").asText("").trim();
                if (!expectedTool.equals(actualTool)) {
                    log.warn("[task-plan] step tool mismatch, index={}, expected={}, actual={}",
                            index, expectedTool, actualTool);
                    return error("任务计划与固定工具契约不一致：第 " + (index + 1)
                            + " 步应为 " + expectedTool + "，实际为 " + actualTool);
                }
                ToolDefinition definition = definitions.get(expectedTool);
                JsonNode argsNode = stepNode.get("args");
                if (argsNode != null && !argsNode.isNull() && !argsNode.isObject()) {
                    return error("工具 " + expectedTool + " 的参数必须是 JSON 对象");
                }
                ToolInputValidator.Result validation =
                        ToolInputValidator.validate(definition, argsNode == null ? null : argsNode);
                if (!validation.valid()) {
                    log.warn("[task-plan] invalid args, tool={}, reason={}",
                            expectedTool, validation.message());
                    return error("工具 " + expectedTool + " 参数不合法：" + validation.message());
                }
                Map<String, Object> args = argsNode != null && argsNode.isObject()
                        ? JsonUtil.mapper().convertValue(argsNode,
                        new TypeReference<Map<String, Object>>() {
                        }) : Map.of();
                Map<String, Object> step = new LinkedHashMap<>();
                step.put("workflowId", workflow.id());
                step.put("stage", stageByTool.get(expectedTool));
                step.put("tool", expectedTool);
                step.put("args", args);
                step.put("purpose", stepNode.path("purpose").asText(""));
                step.put("sideEffect", definition.sideEffect().name());
                plan.add(step);
            }

            // 步骤6：写操作不直接执行，保留完整计划并转确认节点。
            List<Map<String, Object>> writeSteps = new ArrayList<>();
            for (Map<String, Object> step : plan) {
                ToolDefinition definition = definitions.get(String.valueOf(step.get("tool")));
                if (definition.sideEffect() != ToolSideEffect.READ_ONLY) {
                    Map<String, Object> writeStep = new LinkedHashMap<>(step);
                    writeStep.put("risk", definition.sideEffect().name());
                    writeSteps.add(writeStep);
                }
            }
            if (writeSteps.size() > 1) {
                log.warn("[task-plan] multiple write steps rejected, workflow={}, count={}",
                        workflow.id(), writeSteps.size());
                return error("一次只能确认一个写操作，请拆分为多轮处理");
            }

            updates.put(WorkflowState.TASK_ENTITIES, entities);
            updates.put(WorkflowState.TASK_MISSING, List.of());
            updates.put(WorkflowState.TASK_PLAN, plan);
            updates.put(WorkflowState.TASK_PENDING_ACTION,
                    writeSteps.isEmpty() ? Map.of() : writeSteps.get(0));
            updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, !writeSteps.isEmpty());
            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-plan] done, workflow={}, steps={}, writeConfirmation={}, entities={}",
                    workflow.id(), plan.size(), !writeSteps.isEmpty(), entities.keySet());
            return updates;
        } catch (BizException e) {
            log.warn("[task-plan] llm failed: {}", e.getMessage());
            return error("任务计划生成失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[task-plan] unexpected error", e);
            return error("任务计划解析失败: " + e.getMessage());
        }
    }

    private List<Map<String, Object>> workflowCatalog(List<TaskWorkflow> workflows) {
        List<Map<String, Object>> catalog = new ArrayList<>(workflows.size());
        for (TaskWorkflow workflow : workflows) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", workflow.id());
            item.put("description", workflow.description());
            List<Map<String, Object>> stages = new ArrayList<>();
            for (TaskWorkflow.Stage stage : workflow.stages()) {
                Map<String, Object> stageItem = new LinkedHashMap<>();
                stageItem.put("id", stage.id());
                stageItem.put("description", stage.description());
                stages.add(stageItem);
            }
            item.put("stages", stages);
            catalog.add(item);
        }
        return catalog;
    }

    private Map<String, String> stageByTool(TaskWorkflow workflow) {
        Map<String, String> result = new LinkedHashMap<>();
        for (TaskWorkflow.Stage stage : workflow.stages()) {
            for (String tool : stage.tools()) {
                result.put(tool, stage.id());
            }
        }
        return result;
    }

    private List<String> parseMissing(JsonNode missingNode) {
        List<String> missing = new ArrayList<>();
        if (missingNode != null && missingNode.isArray()) {
            missingNode.forEach(item -> {
                String slot = item.asText("").trim();
                if (!slot.isBlank()) {
                    missing.add(slot);
                }
            });
        }
        return missing;
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        return updates;
    }

    private String slotLabels(List<String> slots) {
        List<String> labels = new ArrayList<>(slots.size());
        for (String slot : slots) {
            labels.add(slot == null || slot.isBlank() ? "必要参数" : "参数（" + slot + "）");
        }
        return String.join("、", labels);
    }
}
