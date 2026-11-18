package com.example.langchain4j.graph.qa;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.knowledge.KnowledgeRetrievalResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 证据评估单测：空结果、关键词覆盖、置信度与充分性判定。 */
class EvidenceAssessorTest {

    private EvidenceAssessor assessor;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getRetrieval().setMinEvidenceConfidence(0.45);
        properties.getRetrieval().setMinKeywordCoverage(0.20);
        properties.getRetrieval().setRrfScoreScale(0.033);
        assessor = new EvidenceAssessor(properties);
    }

    @Test
    @DisplayName("空检索 → 无证据、低置信、判定不充分")
    void emptyRetrievalIsInsufficient() {
        WorkflowState.RetrievalEvidence evidence =
                assessor.assess("JVM 内存模型", KnowledgeRetrievalResult.empty());

        assertThat(evidence.sufficient()).isFalse();
        assertThat(evidence.confidence()).isZero();
        assertThat(evidence.hitCount()).isZero();
    }

    @Test
    @DisplayName("上下文覆盖问题关键词 → 证据充分")
    void coveredContextIsSufficient() {
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(
                "【参考资料 1】JVM 内存模型包含堆、栈、方法区和程序计数器。", 2, 0.032);

        WorkflowState.RetrievalEvidence evidence = assessor.assess("JVM 内存模型是什么", result);

        assertThat(evidence.keywordCoverage()).isGreaterThanOrEqualTo(0.20);
        assertThat(evidence.confidence()).isGreaterThanOrEqualTo(0.45);
        assertThat(evidence.sufficient()).isTrue();
    }

    @Test
    @DisplayName("上下文与问题无关 → 覆盖率不足、判定不充分")
    void unrelatedContextIsInsufficient() {
        KnowledgeRetrievalResult result = new KnowledgeRetrievalResult(
                "【参考资料 1】今天食堂供应红烧肉和米饭。", 1, 0.016);

        WorkflowState.RetrievalEvidence evidence = assessor.assess("JVM 垃圾回收算法", result);

        assertThat(evidence.keywordCoverage()).isLessThan(0.20);
        assertThat(evidence.sufficient()).isFalse();
    }

    @Test
    @DisplayName("回答支撑度：命中资料关键词越高，支撑越强")
    void answerSupportUsesContextOverlap() {
        double strong = assessor.answerSupport("JVM 内存模型包含堆和栈【参考资料 1】",
                "JVM 内存模型包含堆、栈、方法区。");
        double weak = assessor.answerSupport("完全无关的回答", "JVM 内存模型包含堆、栈、方法区。");

        assertThat(strong).isGreaterThan(weak);
    }
}
