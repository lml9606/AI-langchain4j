package com.example.langchain4j.graph.qa.edges;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 证据路由单测：充分走生成、不足升级完整检索、完整仍空转人工。 */
class RouterTest {

    private Router router;

    @BeforeEach
    void setUp() {
        router = new Router(new AgentProperties());
    }

    @Test
    @DisplayName("探针证据充分且计划简单 → 直接生成")
    void sufficientProbeGoesToGenerate() throws Exception {
        WorkflowState state = stateOf(QaIntent.KNOWLEDGE_QA, "有资料", simplePlan(),
                new WorkflowState.RetrievalEvidence(2, 0.03, 0.8, 0.8, true, "ok"), "probe");

        assertThat(router.afterRetrievalProbe().apply(state)).isEqualTo(Router.TO_GENERATE);
    }

    @Test
    @DisplayName("探针证据不足 → 升级完整检索")
    void insufficientProbeGoesToFullRetrieval() throws Exception {
        WorkflowState state = stateOf(QaIntent.KNOWLEDGE_QA, "弱资料", simplePlan(),
                new WorkflowState.RetrievalEvidence(1, 0.01, 0.1, 0.2, false, "weak"), "probe");

        assertThat(router.afterRetrievalProbe().apply(state)).isEqualTo(Router.TO_RETRIEVAL_FULL);
    }

    @Test
    @DisplayName("复杂计划即使探针命中 → 仍升级完整检索")
    void compoundPlanGoesToFullRetrieval() throws Exception {
        WorkflowState.QueryPlan plan = new WorkflowState.QueryPlan(
                "A和B", List.of("A", "B"), "compound", "medium", true, "compound");
        WorkflowState state = stateOf(QaIntent.KNOWLEDGE_QA, "有资料", plan,
                new WorkflowState.RetrievalEvidence(3, 0.03, 0.9, 0.9, true, "ok"), "probe");

        assertThat(router.afterRetrievalProbe().apply(state)).isEqualTo(Router.TO_RETRIEVAL_FULL);
    }

    @Test
    @DisplayName("deep 模式即使探针命中 → 仍升级完整检索以使用更宽召回")
    void deepModeGoesToFullRetrieval() throws Exception {
        WorkflowState state = new WorkflowState(Map.of(
                WorkflowState.INTENT, QaIntent.KNOWLEDGE_QA,
                WorkflowState.CONTEXT, "有资料",
                WorkflowState.QUERY_PLAN, simplePlan(),
                WorkflowState.RETRIEVAL_EVIDENCE,
                new WorkflowState.RetrievalEvidence(3, 0.03, 0.9, 0.9, true, "ok"),
                WorkflowState.RETRIEVAL_DEPTH, "probe",
                WorkflowState.ANSWER_MODE, "deep"));

        assertThat(router.afterRetrievalProbe().apply(state)).isEqualTo(Router.TO_RETRIEVAL_FULL);
    }

    @Test
    @DisplayName("完整检索仍为空 → 转人工")
    void emptyFullRetrievalGoesToHandoff() throws Exception {
        WorkflowState state = stateOf(QaIntent.KNOWLEDGE_QA, "", simplePlan(),
                WorkflowState.RetrievalEvidence.empty(), "full");

        assertThat(router.afterRetrievalFull().apply(state)).isEqualTo(Router.TO_HANDOFF);
    }

    @Test
    @DisplayName("代码生成无资料 → 仍直接生成，不误转人工")
    void codeGenerationDoesNotRequireKnowledge() throws Exception {
        WorkflowState.QueryPlan plan = new WorkflowState.QueryPlan(
                "写一个快排", List.of("写一个快排"), "simple", "low", false, "code");
        WorkflowState state = stateOf(QaIntent.CODE_GENERATE, "", plan,
                WorkflowState.RetrievalEvidence.empty(), "probe");

        assertThat(router.afterRetrievalProbe().apply(state)).isEqualTo(Router.TO_GENERATE);
    }

    private WorkflowState.QueryPlan simplePlan() {
        return new WorkflowState.QueryPlan("JVM", List.of("JVM"), "simple", "low", false, "simple");
    }

    private WorkflowState stateOf(String intent, String context, WorkflowState.QueryPlan plan,
                                  WorkflowState.RetrievalEvidence evidence, String depth) {
        return new WorkflowState(Map.of(
                WorkflowState.INTENT, intent,
                WorkflowState.CONTEXT, context,
                WorkflowState.QUERY_PLAN, plan,
                WorkflowState.RETRIEVAL_EVIDENCE, evidence,
                WorkflowState.RETRIEVAL_DEPTH, depth));
    }
}
