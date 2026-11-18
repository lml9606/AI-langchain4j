package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.EvidenceAssessor;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.knowledge.KnowledgeRetrievalResult;
import com.example.langchain4j.knowledge.VectorSearchService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 轻量检索探针：先用主查询做一次低成本检索，拿到证据后再决定是否升级完整检索。
 * <p>
 * 该节点不调用 LLM，只负责把“检索到了什么”结构化写入状态；是否继续、是否生成、
 * 是否转人工由 Router 依据证据置信度判断。
 */
@Slf4j
@Component
public class RetrievalProbeNode implements NodeAction<WorkflowState> {

    private final VectorSearchService vectorSearchService;
    private final EvidenceAssessor evidenceAssessor;
    private final AgentProperties properties;

    public RetrievalProbeNode(VectorSearchService vectorSearchService,
                              EvidenceAssessor evidenceAssessor,
                              AgentProperties properties) {
        this.vectorSearchService = vectorSearchService;
        this.evidenceAssessor = evidenceAssessor;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        long start = System.currentTimeMillis();
        String query = state.queryPlan().primaryQuery();
        if (query.isBlank()) {
            query = state.effectiveQuery();
        }
        int topK = Math.max(1, properties.getRetrieval().getProbeTopK());
        KnowledgeRetrievalResult result = vectorSearchService.retrieve(
                query, topK, privateOnly(state));
        WorkflowState.RetrievalEvidence evidence = evidenceAssessor.assess(query, result);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.CONTEXT, result.context());
        updates.put(WorkflowState.RETRIEVAL_EVIDENCE, evidence);
        updates.put(WorkflowState.RETRIEVAL_DEPTH, "probe");
        updates.put(WorkflowState.CONTEXT_INCOMPLETE, false);
        if (!result.hasContext() && QaIntent.KNOWLEDGE_QA.equals(state.intent())) {
            // 探针为空只是预判；完整检索仍可能用规划子查询补到资料，最终以完整检索结果为准。
            updates.put(WorkflowState.ROOT_CAUSE, WorkflowState.ROOT_CAUSE_KNOWLEDGE_MISSING);
        } else {
            updates.put(WorkflowState.ROOT_CAUSE, "");
        }
        log.info("[retrieval-probe] cost={}ms, queryLength={}, topK={}, contextLength={}, hits={}, confidence={}, sufficient={}, reason={}",
                System.currentTimeMillis() - start, query.length(), topK, result.context().length(),
                result.hitCount(), evidence.confidence(), evidence.sufficient(), evidence.reason());
        return updates;
    }

    private boolean privateOnly(WorkflowState state) {
        return "private".equals(state.visibility());
    }
}
