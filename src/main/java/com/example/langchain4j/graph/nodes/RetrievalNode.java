package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识检索节点：pgvector 语义检索，为生成节点提供上下文。
 * <p>
 * 三种模式（按状态字段组合判别）：
 * <ul>
 *   <li>首轮检索：遍历问题分解后的子查询各自检索（并列复合问题分别召回），按子问题分组标注合并；未分解时用有效查询单查；</li>
 *   <li>多跳检索：HopCheck 判定信息不足路由回来，用下一跳查询（pendingQuery）检索并把新上下文追加合并（保留证据链）；</li>
 *   <li>纠错重查：质检发现回答与资料矛盾（grounding）路由回来，先让 LLM 根据失败原因改写 query 再检索，替换原上下文。</li>
 * </ul>
 * 根因判定（仅首轮可判定，多跳/重查轮不覆盖）：首轮上下文为空 → 知识缺失（KNOWLEDGE_MISSING）；
 * 真正发生分解且命中 → 问题复杂（COMPLEX_QUERY）；未分解单查命中 → 无根因。
 * <p>
 * 防御策略：检索失败（DB/向量服务不可用）返回空上下文，不阻断主流程。
 */
@Slf4j
@Component
public class RetrievalNode implements NodeAction<WorkflowState> {

    /** 上下文总长度上限：多跳追加时防上下文膨胀撑爆生成窗口 */
    private static final int MAX_CONTEXT_LENGTH = 16000;

    private final VectorSearchService vectorSearchService;
    private final LlmGateway llmGateway;

    public RetrievalNode(VectorSearchService vectorSearchService, LlmGateway llmGateway) {
        this.vectorSearchService = vectorSearchService;
        this.llmGateway = llmGateway;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：记录开始时间，用于统计检索耗时
        long start = System.currentTimeMillis();
        // 步骤2：按状态字段组合分派检索模式并判定根因（见 resolveContext）
        RetrievalResult result = resolveContext(state);
        // 步骤3：记录检索耗时与上下文长度，便于观测检索质量
        log.info("[retrieval] cost={}ms, requery={}, hops={}, contextLength={}, rootCause={}",
                System.currentTimeMillis() - start, !state.validationIssue().isBlank(),
                state.hopCount(), result.context().length(), result.rootCause());
        // 步骤4：返回增量更新：检索上下文与根因写入状态，供生成/路由节点使用
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.CONTEXT, result.context());
        updates.put(WorkflowState.ROOT_CAUSE, result.rootCause());
        return updates;
    }

    /**
     * 分派检索模式并产出上下文与根因：
     * <ul>
     *   <li>纠错重查（grounding）→ 替换上下文，根因沿用；</li>
     *   <li>多跳检索（pendingQuery）→ 追加合并，根因沿用（HopCheckNode 负责补查类根因）；</li>
     *   <li>首轮检索 → 遍历子查询合并，同时判定根因（知识缺失/问题复杂，仅首轮可判定）。</li>
     * </ul>
     */
    private RetrievalResult resolveContext(WorkflowState state) {
        if (ValidateNode.ISSUE_GROUNDING.equals(state.validationIssue())) {
            // 纠错重查：原上下文已证与回答矛盾，替换而非追加
            return new RetrievalResult(requery(state), state.rootCause());
        }
        if (!state.pendingQuery().isBlank()) {
            // 多跳检索：用下一跳查询检索，追加到已有上下文（回答需要完整证据链）
            return new RetrievalResult(appendHop(state), state.rootCause());
        }
        // 首轮检索：遍历子查询（分解后的独立查询点）各自召回合并；同时判定根因
        String context = firstRound(state);
        return new RetrievalResult(context, resolveFirstRoundRootCause(state, context));
    }

    /** 检索产物：检索到的上下文 + 检索根因（多跳/重查轮根因沿用，仅首轮判定） */
    private record RetrievalResult(String context, String rootCause) {
    }

    /**
     * 首轮检索根因判定（仅首轮可判定这两类根因，多跳/重查轮不覆盖）：
     * <ul>
     *   <li>上下文为空 → 知识缺失（所有子查询均未召回，Router 据此转人工，不进入 LLM）；</li>
     *   <li>真正发生分解（子查询数 &gt;1 或单子查询与原查询不同）且命中 → 问题复杂（问法本身没问题，是复合问题）；</li>
     *   <li>未分解单查命中 → 无根因（正常命中，返回空串）。</li>
     * </ul>
     */
    private String resolveFirstRoundRootCause(WorkflowState state, String context) {
        // 步骤1：首轮检索为空 → 知识缺失
        if (context == null || context.isBlank()) {
            return WorkflowState.ROOT_CAUSE_KNOWLEDGE_MISSING;
        }
        // 步骤2：分解真实发生且命中 → 问题复杂
        if (reallyDecomposed(state)) {
            return WorkflowState.ROOT_CAUSE_COMPLEX_QUERY;
        }
        // 步骤3：未分解单查命中 → 无根因
        return "";
    }

    /** 是否真正拆分（排除 DecomposeNode 降级的单查询：降级时子查询与有效查询完全相同，等同未分解） */
    private boolean reallyDecomposed(WorkflowState state) {
        List<String> subs = state.subQueries();
        // 步骤1：多个子查询必然是真实拆分
        if (subs.size() > 1) {
            return true;
        }
        // 步骤2：单子查询且与原查询不同才算拆分；相同即为降级单查询
        return subs.size() == 1 && !subs.get(0).equals(state.effectiveQuery());
    }

    /**
     * 首轮检索：遍历子查询（分解后的独立查询点），各自召回后按子问题分组标注合并。
     * 未分解（subQueries 空）时退化为单查询检索，与旧行为一致。
     */
    private String firstRound(WorkflowState state) {
        List<String> subQueries = state.subQueries();
        // 步骤1：未分解 → 有效查询单查
        if (subQueries.isEmpty()) {
            return vectorSearchService.buildContext(vectorSearchService.search(state.effectiveQuery()));
        }
        // 步骤2：分解后 → 每个子问题独立召回，按子问题分组标注（生成模型可对号入座）
        StringBuilder sb = new StringBuilder();
        int index = 1;
        for (String subQuery : subQueries) {
            String part = vectorSearchService.buildContext(vectorSearchService.search(subQuery));
            if (part.isBlank()) {
                continue;
            }
            sb.append("【子问题 ").append(index).append("：").append(subQuery).append("】\n").append(part);
            index++;
        }
        return sb.toString();
    }

    /**
     * 多跳检索：用下一跳查询（HopCheck 针对缺失信息点生成）检索，新上下文追加到已有上下文之后。
     * 追加以保留前几跳资料 —— 回答依赖完整证据链，替换会丢掉前面的信息；
     * 总长设上限，防多跳上下文膨胀。
     */
    private String appendHop(WorkflowState state) {
        String part = vectorSearchService.buildContext(
                vectorSearchService.search(state.pendingQuery()));
        // 步骤1：本跳无新结果 → 保持原上下文（多跳判断会再次评估，超限后进生成）
        if (part.isBlank()) {
            return state.context();
        }
        // 步骤2：追加合并 + 总长截断防御
        String merged = state.context() + "\n" + part;
        return merged.length() > MAX_CONTEXT_LENGTH ? merged.substring(0, MAX_CONTEXT_LENGTH) : merged;
    }

    /** 纠错重查：根据质检失败原因让 LLM 改写 query 再检索，替换原上下文（原上下文已证与回答矛盾） */
    private String requery(WorkflowState state) {
        String searchQuery = resolveSearchQuery(state);
        return vectorSearchService.buildContext(vectorSearchService.search(searchQuery));
    }

    /**
     * 选择检索 query：重查模式（质检 grounding 失败）时根据失败原因让 LLM 改写 query，
     * 更聚焦矛盾点；改写失败或非重查场景降级为有效查询（不阻断主流程）。
     *
     * @param state 当前状态（含质检失败类型/原因）
     * @return 实际用于向量检索的 query
     */
    private String resolveSearchQuery(WorkflowState state) {
        if (!ValidateNode.ISSUE_GROUNDING.equals(state.validationIssue())) {
            return state.effectiveQuery();
        }
        String userPrompt = "原查询：\n" + state.effectiveQuery()
                + "\n\n质检失败原因：\n" + state.validationReason();
        try {
            String refined = llmGateway.generate(state.model(),
                    PromptTemplates.REQUERY_SYSTEM, userPrompt).text();
            String cleaned = clean(refined);
            if (!cleaned.isBlank()) {
                log.info("[retrieval] requery refined: {} -> {}", state.effectiveQuery(), cleaned);
                return cleaned;
            }
        } catch (Exception e) {
            log.warn("[retrieval] requery llm failed, fallback to original query: {}", e.getMessage());
        }
        return state.effectiveQuery();
    }

    /** 控制字符清洗 + 首尾空白去除（与入口清洗规则一致） */
    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
