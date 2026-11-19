package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意图与检索规划节点：一次结构化 LLM 调用同时完成意图分类和知识问答检索计划。
 * <p>
 * 相比旧链路“IntentNode + DecomposeNode”两次调用：
 * <ul>
 *   <li>意图、主查询、子查询、复杂度、风险一次产出，减少一次模型往返；</li>
 *   <li>不再用“问题长度/关键词”规则判断是否跳过分解，所有查询走同一套语义规划；</li>
 *   <li>模型只输出计划，不输出置信度；检索是否充分由后续证据评估器判断。</li>
 * </ul>
 * 防御策略：LLM 失败、解析失败、空计划都降级为“知识问答 + 单查询 + 低风险”，不阻断主链路。
 */
@Slf4j
@Component
public class IntentPlanNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final AgentProperties properties;

    public IntentPlanNode(LlmGateway llmGateway, PromptTemplateService promptTemplateService,
                          AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        String effectiveQuery = state.effectiveQuery();
        if (effectiveQuery == null || effectiveQuery.isBlank()) {
            return degradedPlan(state, "空查询");
        }
        long start = System.currentTimeMillis();
        try {
            int maxSubQueries = Math.max(1, properties.getMaxSubQueries());
            String systemPrompt = promptTemplateService.resolveFormatted(
                    "INTENT_PLAN_SYSTEM", PromptTemplates.INTENT_PLAN_SYSTEM, maxSubQueries);
            JsonNode node = llmGateway.generateJson(LlmRequest.of(state.model(), systemPrompt,
                    buildUserPrompt(state, effectiveQuery)).scene("intent-plan"));
            if (node == null) {
                return degradedPlan(state, "输出截断或解析失败");
            }
            String intent = QaIntent.normalize(node.path("intent").asText(""));
            String reason = clean(node.path("reason").asText(""));
            String primaryQuery = retrievalQuery(node.path("primaryQuery").asText(""),
                    effectiveQuery);
            List<String> subQueries = readSubQueries(node, primaryQuery, maxSubQueries);
            String complexity = normalizeComplexity(node.path("complexity").asText(""),
                    subQueries.size());
            String risk = normalizeRisk(node.path("risk").asText(""));
            boolean requiresFull = QaIntent.isKnowledge(intent)
                    && ("compound".equals(complexity) || subQueries.size() > 1);
            WorkflowState.QueryPlan plan = new WorkflowState.QueryPlan(
                    primaryQuery, subQueries, complexity, risk, requiresFull, reason);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.INTENT, intent);
            updates.put(WorkflowState.INTENT_REASON, reason);
            updates.put(WorkflowState.QUERY_PLAN, plan);
            updates.put(WorkflowState.SUB_QUERIES, subQueries);
            updates.put(WorkflowState.ERROR, "");
            log.info("[intent-plan] cost={}ms, intent={}, complexity={}, risk={}, primaryLength={}, subQueries={}, reasonLength={}",
                    System.currentTimeMillis() - start, intent, complexity, risk, primaryQuery.length(),
                    subQueries.size(), reason.length());
            return updates;
        } catch (Exception e) {
            log.warn("[intent-plan] cost={}ms, llm/parse failed, degrade to default plan: {}",
                    System.currentTimeMillis() - start, e.getMessage());
            return degradedPlan(state, "模型降级：" + e.getMessage());
        }
    }

    /** 双视图输入：原句保留并列结构，改写句补充指代消解。 */
    private String buildUserPrompt(WorkflowState state, String effectiveQuery) {
        String original = state.query();
        if (original == null || original.isBlank() || original.equals(effectiveQuery)) {
            return effectiveQuery;
        }
        return "用户原句：\n" + original + "\n\n多轮改写句（指代已消解，仅作参照）：\n" + effectiveQuery;
    }

    /** 降级计划：简单对话 + 单查询；不检索、不调工具，避免分类失败时误执行。 */
    private Map<String, Object> degradedPlan(WorkflowState state, String reason) {
        String query = retrievalQuery(state.effectiveQuery(), state.query());
        List<String> subQueries = query.isBlank() ? List.of() : List.of(query);
        WorkflowState.QueryPlan plan = new WorkflowState.QueryPlan(
                query, subQueries, "simple", "low", false, reason);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.INTENT, QaIntent.DEFAULT);
        updates.put(WorkflowState.INTENT_REASON, reason);
        updates.put(WorkflowState.QUERY_PLAN, plan);
        updates.put(WorkflowState.SUB_QUERIES, subQueries);
        updates.put(WorkflowState.ERROR, "");
        log.warn("[intent-plan] degraded, intent={}, queryLength={}, reason={}",
                QaIntent.DEFAULT, query.length(), reason);
        return updates;
    }

    private List<String> readSubQueries(JsonNode node, String primaryQuery, int maxSubQueries) {
        List<String> result = new ArrayList<>();
        JsonNode array = node.path("subQueries");
        if (array.isArray()) {
            for (JsonNode item : array) {
                if (result.size() >= maxSubQueries) {
                    break;
                }
                String query = retrievalQuery(item.asText(""), "");
                if (!query.isBlank() && !result.contains(query)) {
                    result.add(query);
                }
            }
        }
        if (result.isEmpty() && !primaryQuery.isBlank()) {
            result.add(primaryQuery);
        }
        return result;
    }

    private String retrievalQuery(String candidate, String fallback) {
        String cleaned = clean(candidate);
        if (cleaned.isBlank()) {
            cleaned = clean(fallback);
        }
        if (cleaned.isBlank()) {
            return "";
        }
        int maxLength = properties.getLimits().getMaxRetrievalQueryLength();
        return cleaned.length() > maxLength ? cleaned.substring(0, maxLength) : cleaned;
    }

    private String normalizeComplexity(String value, int subQueryCount) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if ("compound".equals(normalized) || "simple".equals(normalized)) {
            return normalized;
        }
        return subQueryCount > 1 ? "compound" : "simple";
    }

    private String normalizeRisk(String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        return switch (normalized) {
            case "medium", "high", "low" -> normalized;
            default -> "low";
        };
    }

    private String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
