package com.example.langchain4j.graph.qa;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 回答预算单测：简单短答、复合展开、deep 最长，代码只限 token 不限字数。 */
class AnswerBudgetResolverTest {

    private AgentProperties properties;
    private AnswerBudgetResolver resolver;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        resolver = new AnswerBudgetResolver(properties);
    }

    @Test
    @DisplayName("简单知识问答 → 500 字 / 1024 token，并限制最多 3 条关键点")
    void simpleKnowledgeBudget() {
        AnswerBudget budget = resolver.resolve(stateOf(QaIntent.KNOWLEDGE_QA, "simple", 1, "auto"));

        assertThat(budget.maxChars()).isEqualTo(500);
        assertThat(budget.maxOutputTokens()).isEqualTo(1024);
        assertThat(budget.styleNote()).contains("最多 3 条关键点");
    }

    @Test
    @DisplayName("复合知识问答 → 900 字 / 1536 token，按子问题分组回答")
    void compoundKnowledgeBudget() {
        AnswerBudget budget = resolver.resolve(stateOf(QaIntent.KNOWLEDGE_QA, "compound", 2, "auto"));

        assertThat(budget.maxChars()).isEqualTo(900);
        assertThat(budget.maxOutputTokens()).isEqualTo(1536);
        assertThat(budget.styleNote()).contains("按子问题逐一回答");
    }

    @Test
    @DisplayName("deep 模式 → 覆盖复杂度，使用最长预算")
    void deepKnowledgeBudgetOverridesComplexity() {
        AnswerBudget budget = resolver.resolve(stateOf(QaIntent.KNOWLEDGE_QA, "compound", 2, "deep"));

        assertThat(budget.maxChars()).isEqualTo(2800);
        assertThat(budget.maxOutputTokens()).isEqualTo(4096);
    }

    @Test
    @DisplayName("代码生成 → 不注入字数限制，只保留 token 硬上限")
    void codeBudgetHasNoCharLimit() {
        AnswerBudget budget = resolver.resolve(stateOf(QaIntent.CODE_GENERATE, "simple", 1, "auto"));

        assertThat(budget.maxChars()).isZero();
        assertThat(budget.maxOutputTokens()).isEqualTo(6144);
        assertThat(budget.hasCharLimit()).isFalse();
    }

    @Test
    @DisplayName("配置可覆盖：修改 knowledge-simple 后即时生效")
    void configOverrideApplies() {
        properties.getLlm().getAnswerBudget().getKnowledgeSimple().setMaxChars(320);
        properties.getLlm().getAnswerBudget().getKnowledgeSimple().setMaxOutputTokens(768);

        AnswerBudget budget = resolver.resolve(stateOf(QaIntent.KNOWLEDGE_QA, "simple", 1, "auto"));

        assertThat(budget.maxChars()).isEqualTo(320);
        assertThat(budget.maxOutputTokens()).isEqualTo(768);
    }

    private WorkflowState stateOf(String intent, String complexity, int subQueryCount, String answerMode) {
        List<String> subQueries = java.util.stream.IntStream.range(0, subQueryCount)
                .mapToObj(i -> "子问题" + (i + 1))
                .toList();
        WorkflowState.QueryPlan plan = new WorkflowState.QueryPlan(
                "主查询", subQueries, complexity, "low", subQueryCount > 1, "test");
        return new WorkflowState(Map.of(
                WorkflowState.INTENT, intent,
                WorkflowState.QUERY_PLAN, plan,
                WorkflowState.ANSWER_MODE, answerMode));
    }
}
