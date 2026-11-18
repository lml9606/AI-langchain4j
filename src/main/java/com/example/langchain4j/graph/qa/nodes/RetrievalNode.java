package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.EvidenceAssessor;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.knowledge.KnowledgeRetrievalResult;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 完整检索节点：只由证据路由按需升级进入，负责多子查询召回与证据重评。
 * <p>
 * 两种模式：
 * <ul>
 *   <li>按计划检索：遍历 IntentPlanNode 产出的主查询/子查询，分别召回后合并上下文；</li>
 *   <li>纠错重查：质检发现回答与资料矛盾时，根据失败原因改写查询后替换上下文。</li>
 * </ul>
 * 与旧链路不同：不再由 HopCheck 反复调用 LLM 判断多跳，完整检索最多执行一次；
 * 检索后立即用 {@link EvidenceAssessor} 做结构化评估，路由据此生成或转人工。
 */
@Slf4j
@Component
public class RetrievalNode implements NodeAction<WorkflowState> {

    private final VectorSearchService vectorSearchService;
    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final EvidenceAssessor evidenceAssessor;
    private final AgentProperties properties;

    public RetrievalNode(VectorSearchService vectorSearchService, LlmGateway llmGateway,
                         PromptTemplateService promptTemplateService,
                         EvidenceAssessor evidenceAssessor,
                         AgentProperties properties) {
        this.vectorSearchService = vectorSearchService;
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.evidenceAssessor = evidenceAssessor;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        long start = System.currentTimeMillis();
        boolean requery = ValidateNode.ISSUE_GROUNDING.equals(state.validation().issue());
        List<String> queries = requery
                ? List.of(resolveSearchQuery(state))
                : retrievalQueries(state);
        int topK = topK(state);
        int perQueryBudget = perQueryContextBudget(queries.size());
        int totalBudget = totalContextBudget(queries.size());

        StringBuilder merged = new StringBuilder();
        int hitCount = 0;
        double topScore = 0.0;
        int matchedQueries = 0;
        for (String query : queries) {
            KnowledgeRetrievalResult part = vectorSearchService.retrieve(
                    query, topK, privateOnly(state), perQueryBudget);
            hitCount += part.hitCount();
            topScore = Math.max(topScore, part.topScore());
            if (!part.hasContext()) {
                continue;
            }
            if (queries.size() > 1) {
                if (!merged.isEmpty()) {
                    merged.append('\n');
                }
                merged.append("【子问题 ").append(matchedQueries + 1).append("：")
                      .append(query).append("】\n");
            }
            if (!merged.isEmpty() && !merged.toString().endsWith("\n")) {
                merged.append('\n');
            }
            merged.append(part.context());
            matchedQueries++;
        }

        String context = merged.length() > totalBudget
                ? merged.substring(0, totalBudget) : merged.toString();
        WorkflowState.RetrievalEvidence evidence = evidenceAssessor.assess(
                state.effectiveQuery(), new KnowledgeRetrievalResult(context, hitCount, topScore));
        String rootCause = resolveRootCause(state, context, queries.size());

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.CONTEXT, context);
        updates.put(WorkflowState.RETRIEVAL_EVIDENCE, evidence);
        updates.put(WorkflowState.RETRIEVAL_DEPTH, requery ? "requery" : "full");
        updates.put(WorkflowState.CONTEXT_INCOMPLETE, !context.isBlank() && !evidence.sufficient());
        updates.put(WorkflowState.ROOT_CAUSE, rootCause);
        log.info("[retrieval-full] cost={}ms, requery={}, queries={}, matchedQueries={}, topK={}, perQueryBudget={}, totalBudget={}, visibility={}, contextLength={}, hits={}, confidence={}, sufficient={}, rootCause={}",
                System.currentTimeMillis() - start, requery, queries.size(), matchedQueries, topK,
                perQueryBudget, totalBudget, state.visibility(), context.length(), hitCount, evidence.confidence(),
                evidence.sufficient(), rootCause);
        return updates;
    }

    /** 完整检索查询集合：优先使用规划的多个子查询；空规划回退有效查询。 */
    private List<String> retrievalQueries(WorkflowState state) {
        Set<String> queries = new LinkedHashSet<>();
        for (String query : state.subQueries()) {
            if (query != null && !query.isBlank()) {
                queries.add(query.trim());
            }
        }
        if (queries.isEmpty()) {
            String primary = state.queryPlan().primaryQuery();
            queries.add(primary.isBlank() ? state.effectiveQuery() : primary);
        }
        return List.copyOf(queries);
    }

    /** 根因：完整检索仍为空 → 知识缺失；多子查询命中 → 问题复杂；普通命中不写根因。 */
    private String resolveRootCause(WorkflowState state, String context, int queryCount) {
        if (context == null || context.isBlank()) {
            return WorkflowState.ROOT_CAUSE_KNOWLEDGE_MISSING;
        }
        if (queryCount > 1 || state.queryPlan().requiresFullRetrieval()) {
            return WorkflowState.ROOT_CAUSE_COMPLEX_QUERY;
        }
        // 探针为空时曾预写 KNOWLEDGE_MISSING；完整检索已命中后必须清掉，否则会把成功路径误报为知识缺失。
        return "";
    }

    /** auto 使用完整检索候选数，deep 放宽。 */
    private int topK(WorkflowState state) {
        AgentProperties.Retrieval retrieval = properties.getRetrieval();
        return Math.max(1, "deep".equals(state.answerMode())
                ? retrieval.getDeepTopK() : retrieval.getFullTopK());
    }

    /** 复合问题每个子查询独立预算；单查询沿用常规上下文预算。 */
    private int perQueryContextBudget(int queryCount) {
        AgentProperties.Retrieval retrieval = properties.getRetrieval();
        int budget = queryCount > 1
                ? retrieval.getCompoundPerQueryContextBudget()
                : retrieval.getContextBudget();
        return Math.max(1, budget);
    }

    /** 多子查询合并后的总预算，防止子查询数量增加后上下文线性膨胀。 */
    private int totalContextBudget(int queryCount) {
        AgentProperties.Retrieval retrieval = properties.getRetrieval();
        int budget = queryCount > 1
                ? retrieval.getCompoundContextBudget()
                : retrieval.getContextBudget();
        return Math.max(1, budget);
    }

    private boolean privateOnly(WorkflowState state) {
        return "private".equals(state.visibility());
    }

    /**
     * 纠错重查查询：根据质检失败原因让 LLM 改写，失败时回退有效查询。
     */
    private String resolveSearchQuery(WorkflowState state) {
        String userPrompt = "原查询：\n" + state.effectiveQuery()
                + "\n\n质检失败原因：\n" + state.validation().reason();
        try {
            String refined = llmGateway.generate(state.model(),
                    promptTemplateService.resolve("REQUERY_SYSTEM", PromptTemplates.REQUERY_SYSTEM),
                    userPrompt).text();
            String cleaned = clean(refined);
            if (!cleaned.isBlank()) {
                log.info("[retrieval-full] requery refined, queryLength={}, refinedLength={}",
                        state.effectiveQuery().length(), cleaned.length());
                return cleaned;
            }
        } catch (Exception e) {
            log.warn("[retrieval-full] requery llm failed, fallback to original query: {}", e.getMessage());
        }
        return state.effectiveQuery();
    }

    private String clean(String text) {
        return text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
