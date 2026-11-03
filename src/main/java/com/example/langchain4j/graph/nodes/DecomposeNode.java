package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
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
 * 防御策略：LLM 失败/解析失败/输出空列表 → 降级为单元素列表（有效查询：改写句优先，
 * 未改写时即原句），与未分解行为完全一致，不阻断主流程。
 */
@Slf4j
@Component
public class DecomposeNode implements NodeAction<WorkflowState> {

    /** 单条子查询最大字符数：防 LLM 输出超长子查询撑爆检索输入 */
    private static final int MAX_SUB_QUERY_LENGTH = 400;

    private final LlmGateway llmGateway;
    private final AgentProperties properties;
    private final PromptTemplateService promptTemplateService;

    public DecomposeNode(LlmGateway llmGateway, AgentProperties properties,
                         PromptTemplateService promptTemplateService) {
        this.llmGateway = llmGateway;
        this.properties = properties;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：调用 LLM 分解子查询（内部 fail-open：任何失败降级为单查询）
        List<String> subQueries = decompose(state);
        // 步骤2：写入状态覆盖 entry 种入的默认单查询，供 RetrievalNode 首轮遍历检索
        log.info("[decompose] subQueries={}", subQueries);
        return Map.of(WorkflowState.SUB_QUERIES, subQueries);
    }

    /**
     * 拼装分解输入：改写句存在且不同于原句时双视图（原句为结构基准，改写句仅补充指代信息），
     * 防改写环节意外吞掉并列结构导致漏拆；否则退化为单视图原句，不增加 token 开销。
     */
    private String buildUserPrompt(WorkflowState state) {
        String original = state.query();
        String rewritten = state.rewrittenQuery();
        if (rewritten == null || rewritten.isBlank() || rewritten.equals(original)) {
            return original;
        }
        return "用户原句：" + original + "\n多轮改写句（指代已消解，仅作参照）：" + rewritten;
    }

    /** 调用 LLM 分解；失败/空输出降级为单元素列表（原查询），保证检索路径始终可用 */
    private List<String> decompose(WorkflowState state) {
        try {
            // 步骤1：子查询上限取配置值（最小 1 防御 0/负数），与提示词、白名单同源，防三处漂移
            int maxSubQueries = Math.max(1, properties.getMaxSubQueries());
            // 提示词走配置化解析：DB 有覆盖用 DB 版（含字面 % 等非法格式时兑底默认模板），否则兑底代码默认值
            String systemPrompt = promptTemplateService.resolveFormatted(
                    "DECOMPOSE_SYSTEM", PromptTemplates.DECOMPOSE_SYSTEM, maxSubQueries);
            // 步骤2：json_object 模式约束输出，网关封装（截断感知 + 提取解析，不可用返回 null）
            // 双视图输入：原句为结构基准 + 改写句补充指代消解（防改写环节吞并列结构导致漏拆）
            JsonNode node = llmGateway.generateJsonNode(state.model(), systemPrompt,
                    buildUserPrompt(state), "decompose");
            // 步骤3：截断/解析失败或 subQueries 缺失/非数组/空数组一律降级为单查询（等同未分解）
            if (node == null) {
                return List.of(state.effectiveQuery());
            }
            JsonNode arr = node.hasNonNull("subQueries") && node.get("subQueries").isArray()
                    ? node.get("subQueries") : null;
            if (arr == null || arr.isEmpty()) {
                log.warn("[decompose] empty subQueries, fallback to single query");
                return List.of(state.effectiveQuery());
            }
            // 步骤4：白名单数量 + 空白过滤 + 控制字符清洗 + 单条截断（防脏输出）
            List<String> result = new ArrayList<>();
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
            // 步骤5：全部无效时降级为单查询
            if (result.isEmpty()) {
                log.warn("[decompose] no valid subQuery, fallback to single query");
                return List.of(state.effectiveQuery());
            }
            return result;
        } catch (Exception e) {
            // 步骤6：LLM/解析异常不阻断主流程 —— 降级为单查询（等同未分解）
            log.warn("[decompose] llm/parse failed, fallback to single query: {}", e.getMessage());
            return List.of(state.effectiveQuery());
        }
    }
}
