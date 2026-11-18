package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.EvidenceAssessor;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.knowledge.KnowledgeRetrievalResult;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 完整检索单测：复合问题必须给每个子查询独立上下文预算。 */
@ExtendWith(MockitoExtension.class)
class RetrievalNodeTest {

    @Mock
    private VectorSearchService vectorSearchService;
    @Mock
    private LlmGateway llmGateway;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private EvidenceAssessor evidenceAssessor;

    private RetrievalNode node;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getRetrieval().setCompoundPerQueryContextBudget(2200);
        properties.getRetrieval().setCompoundContextBudget(8000);
        node = new RetrievalNode(vectorSearchService, llmGateway, promptTemplateService,
                evidenceAssessor, properties);
    }

    @Test
    @DisplayName("复合问题 → 每个子查询使用独立 2200 字上下文预算")
    void compoundQueriesUsePerQueryBudget() {
        KnowledgeRetrievalResult first = new KnowledgeRetrievalResult("A 的资料", 1, 0.03);
        KnowledgeRetrievalResult second = new KnowledgeRetrievalResult("B 的资料", 1, 0.03);
        when(vectorSearchService.retrieve("子问题1", 6, false, 2200)).thenReturn(first);
        when(vectorSearchService.retrieve("子问题2", 6, false, 2200)).thenReturn(second);
        when(evidenceAssessor.assess(anyString(), any(KnowledgeRetrievalResult.class)))
                .thenReturn(new WorkflowState.RetrievalEvidence(2, 0.03, 0.8, 0.8, true, "ok"));

        Map<String, Object> updates = node.apply(stateOfCompound());

        verify(vectorSearchService).retrieve("子问题1", 6, false, 2200);
        verify(vectorSearchService).retrieve("子问题2", 6, false, 2200);
        assertThat((String) updates.get(WorkflowState.CONTEXT))
                .contains("【子问题 1：子问题1】")
                .contains("A 的资料")
                .contains("【子问题 2：子问题2】")
                .contains("B 的资料");
    }

    private WorkflowState stateOfCompound() {
        List<String> subQueries = List.of("子问题1", "子问题2");
        WorkflowState.QueryPlan plan = new WorkflowState.QueryPlan(
                "主查询", subQueries, "compound", "low", true, "test");
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, "主查询",
                WorkflowState.INTENT, QaIntent.KNOWLEDGE_QA,
                WorkflowState.QUERY_PLAN, plan,
                WorkflowState.SUB_QUERIES, subQueries,
                WorkflowState.ANSWER_MODE, "auto"));
    }
}
