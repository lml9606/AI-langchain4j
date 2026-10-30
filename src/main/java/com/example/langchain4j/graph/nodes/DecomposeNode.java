package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 问题分解节点：把并列复合问题拆成独立子查询，各子问题分别检索后合并上下文。
 * <p>
 * 解决的痛点：单向量检索时「A 公司的规则和 B 公司的规则」这类复合问题的
 * 语义被稀释，召回不齐甚至偏科；拆开后每个子查询语义纯净、各自召回。
 * <p>
 * 防御策略：LLM 失败/解析失败/输出空列表 → 降级为单元素列表（原查询），
 * 与未分解行为完全一致，不阻断主流程。
 */
@Slf4j
@Component
public class DecomposeNode implements NodeAction<WorkflowState> {

    /** 单条子查询最大字符数：防 LLM 输出超长子查询撑爆检索输入 */
    private static final int MAX_SUB_QUERY_LENGTH = 400;

    private final LlmGateway llmGateway;
    private final AgentProperties properties;

    public DecomposeNode(LlmGateway llmGateway, AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：调用 LLM 分解子查询（内部 fail-open：任何失败降级为单查询）
        List<String> subQueries = decompose(state);
        // 步骤2：写入状态，供 RetrievalNode 首轮遍历检索
        log.info("[decompose] subQueries={}", subQueries);
        return Map.of(WorkflowState.SUB_QUERIES, subQueries);
    }

    /** 调用 LLM 分解；失败/空输出降级为单元素列表（原查询），保证检索路径始终可用 */
    private List<String> decompose(WorkflowState state) {
        try {
            // 步骤1：json_object 模式约束输出，先提纯 JSON 再解析
            String raw = llmGateway.generateJson(state.model(),
                    PromptTemplates.DECOMPOSE_SYSTEM, state.effectiveQuery()).text();
            JsonNode node = JsonUtil.fromJson(JsonUtil.extractJsonObject(raw), JsonNode.class);
            // 步骤2：读取 subQueries 数组（缺失/非数组/空数组一律降级）
            JsonNode arr = node != null && node.hasNonNull("subQueries") && node.get("subQueries").isArray()
                    ? node.get("subQueries") : null;
            if (arr == null || arr.isEmpty()) {
                log.warn("[decompose] empty subQueries, fallback to single query");
                return List.of(state.effectiveQuery());
            }
            // 步骤3：白名单数量 + 空白过滤 + 控制字符清洗 + 单条截断（防脏输出）
            List<String> result = new ArrayList<>();
            int maxSubQueries = Math.max(1, properties.getMaxSubQueries());
            for (JsonNode item : arr) {
                if (result.size() >= maxSubQueries) {
                    break;
                }
                String sub = item == null ? "" : item.asText("").replaceAll("[\\p{Cntrl}]", " ").trim();
                if (sub.isBlank()) {
                    continue;
                }
                result.add(sub.length() > MAX_SUB_QUERY_LENGTH ? sub.substring(0, MAX_SUB_QUERY_LENGTH) : sub);
            }
            // 步骤4：全部无效时降级为单查询
            if (result.isEmpty()) {
                log.warn("[decompose] no valid subQuery, fallback to single query");
                return List.of(state.effectiveQuery());
            }
            return result;
        } catch (Exception e) {
            // 步骤5：LLM/解析异常不阻断主流程 —— 降级为单查询（等同未分解）
            log.warn("[decompose] llm/parse failed, fallback to single query: {}", e.getMessage());
            return List.of(state.effectiveQuery());
        }
    }
}
