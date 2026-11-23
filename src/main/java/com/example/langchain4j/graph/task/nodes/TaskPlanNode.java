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
import com.example.langchain4j.tool.runtime.ToolAccessPolicy;
import com.example.langchain4j.tool.workflow.TaskWorkflow;
import com.example.langchain4j.tool.workflow.TaskPlanPhase;
import com.example.langchain4j.tool.workflow.TaskPlanValidator;
import com.example.langchain4j.tool.workflow.TaskWorkflowRegistry;
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
 * 工具集合不通过 TopK 召回交给模型自由选择。工作流在代码中声明完整工具集合与顺序，
 * 规划入口先整组校验权限，模型输出后再次校验“不增、不减、不换、不乱序”。
 */
@Slf4j
@Component
public class TaskPlanNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final TaskWorkflowRegistry workflowRegistry;
    private final ToolAccessPolicy accessPolicy;
    private final TaskPlanValidator planValidator;
    private final AgentProperties properties;

    public TaskPlanNode(LlmGateway llmGateway, PromptTemplateService promptTemplateService,
                        TaskWorkflowRegistry workflowRegistry, ToolAccessPolicy accessPolicy,
                        TaskPlanValidator planValidator,
                        AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.workflowRegistry = workflowRegistry;
        this.accessPolicy = accessPolicy;
        this.planValidator = planValidator;
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
            Map<String, ToolDefinition> definitions = new LinkedHashMap<>();
            List<Map<String, Object>> catalog = new ArrayList<>();
            for (ToolDefinition definition : tools) {
                definitions.put(definition.name(), definition);
            }
            // 按工作流位置而不是工具名建立目录：同一个工具可以出现在多个阶段，
            // 用 Map<toolName, stage> 会让所有重复步骤都显示成最后一个阶段。
            for (TaskWorkflow.Stage stage : workflow.stages()) {
                for (String toolName : stage.tools()) {
                    ToolDefinition definition = definitions.get(toolName);
                    if (definition == null) {
                        continue;
                    }
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("stage", stage.id());
                    item.put("name", definition.name());
                    item.put("description", definition.description());
                    item.put("inputSchema", definition.inputSchema());
                    item.put("sideEffect", definition.sideEffect().name());
                    catalog.add(item);
                }
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

            // 步骤5：统一解析固定工具序列和参数 Schema；解析失败直接 fail-closed。
            TaskPlanValidator.ParseResult parsed = planValidator.parseSteps(
                    node.get("steps"), workflow, definitions, null);
            if (!parsed.valid()) {
                log.warn("[task-plan] invalid steps, workflow={}, reason={}",
                        workflow.id(), parsed.message());
                return error(parsed.message());
            }
            List<Map<String, Object>> plan = parsed.plan();
            applyStageIds(plan, workflow);

            // 步骤6：统一复核写操作数量、pending 一致性和可插拔业务规则。
            TaskPlanValidator.PlanValidationResult planValidation = planValidator.validate(
                    TaskPlanPhase.PLAN, workflow, plan, definitions, Map.of());
            if (!planValidation.valid()) {
                log.warn("[task-plan] plan rejected, workflow={}, reason={}",
                        workflow.id(), planValidation.message());
                return error(planValidation.message());
            }
            int maxWrites = Math.max(1, properties.getTask().getMaxWritesPerTask());
            if (planValidation.writeSteps().size() > maxWrites) {
                log.warn("[task-plan] too many writes, workflow={}, writes={}, max={}",
                        workflow.id(), planValidation.writeSteps().size(), maxWrites);
                return error("一次任务最多允许 " + maxWrites + " 个写操作，请拆分后重试");
            }
            Map<String, Object> writeStep = planValidation.writeStep();
            Map<String, Object> pendingAction = Map.of();
            boolean confirmationRequired = writeStep != null;
            if (confirmationRequired) {
                pendingAction = new LinkedHashMap<>(writeStep);
                pendingAction.put("risk", planValidation.writeDefinition().sideEffect().name());
            }

            updates.put(WorkflowState.TASK_ENTITIES, entities);
            updates.put(WorkflowState.TASK_MISSING, List.of());
            updates.put(WorkflowState.TASK_PLAN, plan);
            updates.put(WorkflowState.TASK_PENDING_ACTION, pendingAction);
            updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, confirmationRequired);
            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-plan] done, workflow={}, steps={}, writes={}, entities={}",
                    workflow.id(), plan.size(), planValidation.writeSteps().size(),
                    entities.keySet());
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

    /** 按工作流声明顺序回填 stage：同一工具可以在多个阶段出现，不能靠工具名反查。 */
    private void applyStageIds(List<Map<String, Object>> plan, TaskWorkflow workflow) {
        int index = 0;
        for (TaskWorkflow.Stage stage : workflow.stages()) {
            for (String ignored : stage.tools()) {
                if (index < plan.size()) {
                    plan.get(index).put("stage", stage.id());
                }
                index++;
            }
        }
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
