package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ToolRegistry;
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
 * 任务规划节点：一次 LLM 调用完成“实体抽取 + 缺参判断 + 工具计划生成”。
 * <p>
 * 输出结构化 JSON，节点侧做白名单校验（工具必须已注册、步骤数受限），
 * 缺参时直接生成追问并标记 clarify，由路由跳过工具执行。
 */
@Slf4j
@Component
public class TaskPlanNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;

    /**
     * 注入 LLM、提示词、工具注册表与 Agent 配置。
     */
    public TaskPlanNode(LlmGateway llmGateway, PromptTemplateService promptTemplateService,
                        ToolRegistry toolRegistry, AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    /**
     * 解析实体、判断缺参并生成工具执行计划；写操作转确认，只读操作转执行。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：准备工具清单（名称/描述/参数 Schema），模型只能从清单中选择
        List<Map<String, Object>> catalog = new ArrayList<>();
        for (ToolDefinition definition : toolRegistry.definitions()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", definition.name());
            item.put("description", definition.description());
            item.put("inputSchema", definition.inputSchema());
            item.put("sideEffect", definition.sideEffect().name());
            catalog.add(item);
        }
        int maxSteps = Math.max(1, properties.getTool().getMaxStepsPerTask());
        String systemPrompt = promptTemplateService.resolveFormatted("TASK_PLAN_SYSTEM",
                PromptTemplates.TASK_PLAN_SYSTEM, maxSteps);
        String userPrompt = "用户请求：\n" + state.effectiveQuery() + "\n\n可用工具：\n" +
                JsonUtil.toJson(catalog);
        log.info("[task-plan] start, queryLength={}, tools={}, maxSteps={}",
                state.effectiveQuery().length(), catalog.size(), maxSteps);

        Map<String, Object> updates = new LinkedHashMap<>();
        try {
            // 步骤2：结构化输出计划；截断/解析失败直接标记错误，由路由转兜底
            JsonNode node = llmGateway.generateJson(
                    LlmRequest.of(state.model(), systemPrompt, userPrompt).scene("task-plan"));
            if (node == null) {
                updates.put(WorkflowState.ERROR, "任务计划生成失败：模型输出截断或非 JSON");
                return updates;
            }
            // 步骤3：解析实体槽位
            Map<String, String> entities = new LinkedHashMap<>();
            JsonNode entitiesNode = node.get("entities");
            if (entitiesNode != null && entitiesNode.isObject()) {
                entitiesNode.fields().forEachRemaining(
                        entry -> entities.put(entry.getKey(), entry.getValue().asText("")));
            }
            // 步骤4：解析缺失槽位
            List<String> missing = new ArrayList<>();
            JsonNode missingNode = node.get("missing");
            if (missingNode != null && missingNode.isArray()) {
                missingNode.forEach(item -> {
                    String slot = item.asText("").trim();
                    if (!slot.isBlank()) {
                        missing.add(slot);
                    }
                });
            }
            // 步骤5：解析并校验计划步骤（未知工具丢弃，参数必须是对象，步骤数受限）
            List<Map<String, Object>> plan = new ArrayList<>();
            JsonNode stepsNode = node.get("steps");
            if (stepsNode != null && stepsNode.isArray()) {
                for (JsonNode stepNode : stepsNode) {
                    if (plan.size() >= maxSteps) {
                        break;
                    }
                    String tool = stepNode.path("tool").asText("").trim();
                    if (tool.isBlank() || toolRegistry.find(tool).isEmpty()) {
                        continue;
                    }
                    JsonNode argsNode = stepNode.get("args");
                    Map<String, Object> args = argsNode != null && argsNode.isObject() ?
                            JsonUtil.mapper().convertValue(argsNode,
                                    new TypeReference<Map<String, Object>>() {
                                    }) : Map.of();
                    Map<String, Object> step = new LinkedHashMap<>();
                    step.put("tool", tool);
                    step.put("args", args);
                    step.put("purpose", stepNode.path("purpose").asText(""));
                    plan.add(step);
                }
            }

            // 步骤6：缺参优先 —— 不执行工具，直接生成追问并标记 clarify
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
                log.info("[task-plan] clarification required, missingSlots={}, entityFields={}",
                        missing, entities.keySet());
                return updates;
            }
            // 步骤7：没有可执行步骤视为规划失败（意图为 task 但模型没有给出工具计划）
            if (plan.isEmpty()) {
                log.warn("[task-plan] plan empty, entityFields={}", entities.keySet());
                updates.put(WorkflowState.ERROR, "任务计划为空：未生成可执行步骤");
                return updates;
            }
            // 步骤8：识别写工具 —— 写操作不进入执行，先转确认节点生成一次性令牌
            List<Map<String, Object>> writeSteps = new ArrayList<>();
            for (Map<String, Object> step : plan) {
                String toolName = String.valueOf(step.get("tool"));
                ToolDefinition definition = toolRegistry.find(toolName)
                        .map(AgentTool::definition)
                        .orElse(null);
                // 注册表查不到时按高风险处理：宁可多一次确认，也不能把未知工具当成只读直接执行
                ToolSideEffect sideEffect =
                        definition == null ? ToolSideEffect.DANGEROUS : definition.sideEffect();
                if (sideEffect != ToolSideEffect.READ_ONLY) {
                    Map<String, Object> writeStep = new LinkedHashMap<>(step);
                    writeStep.put("risk", sideEffect.name());
                    writeSteps.add(writeStep);
                }
            }
            // 步骤8.1：一次只允许确认一个写操作，避免一次确认批量修改多处数据
            if (writeSteps.size() > 1) {
                log.warn("[task-plan] multiple write steps rejected, count={}", writeSteps.size());
                updates.put(WorkflowState.ERROR, "一次只能确认一个写操作，请拆分为多轮处理");
                return updates;
            }
            // 步骤8.2：含写工具 → 保留完整计划，交由 task_confirm 生成确认令牌
            if (writeSteps.size() == 1) {
                updates.put(WorkflowState.TASK_ENTITIES, entities);
                updates.put(WorkflowState.TASK_MISSING, List.of());
                updates.put(WorkflowState.TASK_PLAN, plan);
                updates.put(WorkflowState.TASK_PENDING_ACTION, writeSteps.get(0));
                updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, true);
                updates.put(WorkflowState.TASK_CLARIFY, false);
                updates.put(WorkflowState.ERROR, "");
                log.info("[task-plan] write step requires confirmation, tool={}",
                        writeSteps.get(0).get("tool"));
                return updates;
            }
            // 步骤8.3：纯只读计划正常返回，交给执行节点
            updates.put(WorkflowState.TASK_ENTITIES, entities);
            updates.put(WorkflowState.TASK_MISSING, List.of());
            updates.put(WorkflowState.TASK_PLAN, plan);
            updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
            updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-plan] steps={}, entities={}", plan.size(), entities.keySet());
            return updates;
        } catch (BizException e) {
            log.warn("[task-plan] llm failed: {}", e.getMessage());
            updates.put(WorkflowState.ERROR, "任务计划生成失败: " + e.getMessage());
            return updates;
        } catch (Exception e) {
            log.error("[task-plan] unexpected error", e);
            updates.put(WorkflowState.ERROR, "任务计划解析失败: " + e.getMessage());
            return updates;
        }
    }

    /**
     * 槽位说明：任务工具由业务方注册，槽位名不写死，统一按参数名提示用户补充
     */
    private String slotLabels(List<String> slots) {
        List<String> labels = new ArrayList<>(slots.size());
        for (String slot : slots) {
            labels.add(slot == null || slot.isBlank() ? "必要参数" : "参数（" + slot + "）");
        }
        return String.join("、", labels);
    }
}
