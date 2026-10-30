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

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 多跳判断节点：判断已检索上下文是否足以回答用户问题（生成前的主动补全，与质检重查的纠错驱动互补）。
 * <p>
 * 三种走向：
 * <ul>
 *   <li>足够（或判断失败 fail-open）→ hopSufficient=true，路由去生成；若此前经历过多跳补查且尚无根因，
 *       标记根因为表述不当（EXPRESSION_ISSUE：首轮证据链不足，改写补查后才命中）；</li>
 *   <li>不足且未达多跳上限 → 写入下一跳查询、hopCount+1、hopSufficient=false，路由回检索；</li>
 *   <li>不足且已达上限 → 标记上下文不完整与知识缺失（KNOWLEDGE_MISSING）、hopSufficient=true，
 *       带已有资料进生成（生成端声明信息不全）。</li>
 * </ul>
 * 防御策略：LLM 失败/解析失败/不足但未给出下一跳查询 → 一律视为足够放行去生成，不阻断主流程。
 */
@Slf4j
@Component
public class HopCheckNode implements NodeAction<WorkflowState> {

    /** 下一跳查询最大字符数：防 LLM 输出超长查询撑爆检索输入 */
    private static final int MAX_NEXT_QUERY_LENGTH = 400;

    private final LlmGateway llmGateway;
    private final AgentProperties properties;

    public HopCheckNode(LlmGateway llmGateway, AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        return check(state);
    }

    /** 判断上下文完备性，返回路由所需的增量更新 */
    private Map<String, Object> check(WorkflowState state) {
        HopResult result = hopResult(state);
        // 步骤1：上下文足够 → 清空下一跳查询，放行去生成
        if (result.sufficient()) {
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.HOP_SUFFICIENT, true);
            updates.put(WorkflowState.PENDING_QUERY, "");
            // 经历过补查才足够且尚无根因 → 表述不当（首轮证据链不足，改写补查后命中）；
            // 首轮即足够（hopCount=0）不写，保留 RetrievalNode 首轮可能写入的 COMPLEX_QUERY
            if (state.hopCount() > 0 && state.rootCause().isBlank()) {
                updates.put(WorkflowState.ROOT_CAUSE, WorkflowState.ROOT_CAUSE_EXPRESSION_ISSUE);
            }
            return updates;
        }
        int maxHops = Math.max(1, properties.getMaxHops());
        // 步骤2：不足但已达多跳上限 → 标记上下文不完整，带已有资料进生成
        //        （已有部分资料可答，生成端声明边界；不转人工——与「首轮检索就空」场景区分）
        if (state.hopCount() >= maxHops) {
            log.warn("[hop-check] context insufficient but maxHops reached, generate with incomplete context, hops={}",
                    state.hopCount());
            // 补查超限仍不足 → 知识缺失（覆盖此前的复杂/表述根因，终局结论以缺失为准）
            return Map.of(
                    WorkflowState.HOP_SUFFICIENT, true,
                    WorkflowState.PENDING_QUERY, "",
                    WorkflowState.CONTEXT_INCOMPLETE, true,
                    WorkflowState.ROOT_CAUSE, WorkflowState.ROOT_CAUSE_KNOWLEDGE_MISSING);
        }
        // 步骤3：不足且未达上限 → 写入下一跳查询、计数 +1，路由回检索
        log.info("[hop-check] context insufficient, hop {} -> {}, nextQuery={}",
                state.hopCount(), state.hopCount() + 1, result.nextQuery());
        return Map.of(
                WorkflowState.HOP_SUFFICIENT, false,
                WorkflowState.PENDING_QUERY, result.nextQuery(),
                WorkflowState.HOP_COUNT, state.hopCount() + 1);
    }

    /** 调用 LLM 判断；任何失败 fail-open 视为足够（放行去生成），不阻断主流程 */
    private HopResult hopResult(WorkflowState state) {
        // 步骤1：把用户问题与已检索资料交给判断模型（资料已含前几跳内容，可判断证据链是否完整）
        String userPrompt = "用户问题：\n" + state.effectiveQuery()
                + "\n\n已检索到的参考资料：\n" + state.context();
        try {
            String raw = llmGateway.generateJson(state.model(),
                    PromptTemplates.HOP_CHECK_SYSTEM, userPrompt).text();
            JsonNode node = JsonUtil.fromJson(JsonUtil.extractJsonObject(raw), JsonNode.class);
            // 步骤2：sufficient 缺失视为放行（fail-open：漏判优于误杀，与质检同哲学）
            if (node == null || !node.hasNonNull("sufficient")) {
                return HopResult.passed();
            }
            if (node.get("sufficient").asBoolean(false)) {
                return HopResult.passed();
            }
            // 步骤3：不足时读取下一跳查询；缺失/空白视为足够（避免空查询死循环）
            String nextQuery = node.path("nextQuery").asText("")
                    .replaceAll("[\\p{Cntrl}]", " ").trim();
            if (nextQuery.isBlank()) {
                log.warn("[hop-check] insufficient but nextQuery blank, treat as sufficient");
                return HopResult.passed();
            }
            // 步骤4：单条截断（防超长查询）
            if (nextQuery.length() > MAX_NEXT_QUERY_LENGTH) {
                nextQuery = nextQuery.substring(0, MAX_NEXT_QUERY_LENGTH);
            }
            return new HopResult(false, nextQuery);
        } catch (Exception e) {
            // 步骤5：LLM/解析异常 fail-open 放行，不因判断故障阻断主流程
            log.warn("[hop-check] llm/parse failed, treat as sufficient: {}", e.getMessage());
            return HopResult.passed();
        }
    }

    /** 多跳判断结果（sufficient=true 时 nextQuery 为空串）；静态工厂命名避开与 accessor 同名冲突 */
    private record HopResult(boolean sufficient, String nextQuery) {
        static HopResult passed() {
            return new HopResult(true, "");
        }
    }
}
