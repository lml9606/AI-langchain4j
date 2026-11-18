package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.EvidenceAssessor;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.knowledge.KnowledgeRetrievalResult;
import com.example.langchain4j.knowledge.VectorSearchService;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/** 检索探针单测：空结果根因只对知识问答生效，代码生成不能被误标为知识缺失。 */
@ExtendWith(MockitoExtension.class)
class RetrievalProbeNodeTest {

    @Mock
    private VectorSearchService vectorSearchService;
    @Mock
    private EvidenceAssessor evidenceAssessor;

    private RetrievalProbeNode node;

    @BeforeEach
    void setUp() {
        node = new RetrievalProbeNode(vectorSearchService, evidenceAssessor, new AgentProperties());
    }

    @Test
    @DisplayName("代码生成探针为空 → 不写 KNOWLEDGE_MISSING 根因")
    void codeGenerationHasNoKnowledgeMissingRootCause() {
        String query = "写一个快排";
        KnowledgeRetrievalResult empty = new KnowledgeRetrievalResult("", 0, 0.0);
        when(vectorSearchService.retrieve(query, 3, true)).thenReturn(empty);
        when(evidenceAssessor.assess(eq(query), any())).thenReturn(WorkflowState.RetrievalEvidence.empty());

        Map<String, Object> updates = node.apply(stateOf(QaIntent.CODE_GENERATE, query));

        assertThat(updates.get(WorkflowState.ROOT_CAUSE)).isEqualTo("");
    }

    @Test
    @DisplayName("知识问答探针为空 → 预写 KNOWLEDGE_MISSING，等待完整检索覆盖")
    void knowledgeQaMarksMissingRootCause() {
        String query = "不存在的问题";
        KnowledgeRetrievalResult empty = new KnowledgeRetrievalResult("", 0, 0.0);
        when(vectorSearchService.retrieve(query, 3, true)).thenReturn(empty);
        when(evidenceAssessor.assess(eq(query), any())).thenReturn(WorkflowState.RetrievalEvidence.empty());

        Map<String, Object> updates = node.apply(stateOf(QaIntent.KNOWLEDGE_QA, query));

        assertThat(updates.get(WorkflowState.ROOT_CAUSE))
                .isEqualTo(WorkflowState.ROOT_CAUSE_KNOWLEDGE_MISSING);
    }

    private WorkflowState stateOf(String intent, String query) {
        WorkflowState.QueryPlan plan = new WorkflowState.QueryPlan(
                query, List.of(query), "simple", "low", false, "test");
        return new WorkflowState(Map.of(
                WorkflowState.INTENT, intent,
                WorkflowState.QUERY_PLAN, plan,
                WorkflowState.VISIBILITY, "private"));
    }
}
