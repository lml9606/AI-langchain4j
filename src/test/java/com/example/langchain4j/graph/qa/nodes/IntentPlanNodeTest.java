package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 意图与规划节点单测：一次调用产出意图/查询计划，失败时安全降级。 */
@ExtendWith(MockitoExtension.class)
class IntentPlanNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private PromptTemplateService promptTemplateService;

    private IntentPlanNode node;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxSubQueries(3);
        node = new IntentPlanNode(llmGateway, promptTemplateService, properties);
        lenient().when(promptTemplateService.resolveFormatted(anyString(), anyString(), any()))
                .thenAnswer(invocation -> String.format(
                        invocation.getArgument(1), new Object[]{invocation.getArgument(2)}));
    }

    @Test
    @DisplayName("复合知识问题 → 一次调用产出意图、主查询、子查询、复杂度与风险")
    void planCompoundKnowledgeQuestion() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("""
                        {"intent":"knowledge_qa","primaryQuery":"A公司和B公司的退款规则",
                         "subQueries":["A公司退款规则","B公司退款规则"],"complexity":"compound",
                         "risk":"medium","reason":"两个实体需要分别核对"}
                        """));

        Map<String, Object> updates = node.apply(stateOf("A公司和B公司的退款规则分别是什么"));

        WorkflowState.QueryPlan plan = (WorkflowState.QueryPlan) updates.get(WorkflowState.QUERY_PLAN);
        assertThat(updates.get(WorkflowState.INTENT)).isEqualTo(QaIntent.KNOWLEDGE_QA);
        assertThat(plan.primaryQuery()).isEqualTo("A公司和B公司的退款规则");
        assertThat(plan.subQueries()).containsExactly("A公司退款规则", "B公司退款规则");
        assertThat(plan.requiresFullRetrieval()).isTrue();
        assertThat(plan.risk()).isEqualTo("medium");
    }

    @Test
    @DisplayName("代码意图 → 不触发完整检索规划")
    void planCodeIntent() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("""
                        {"intent":"code_generate","primaryQuery":"写一个 Java 快排",
                         "subQueries":["写一个 Java 快排"],"complexity":"simple",
                         "risk":"low","reason":"代码生成"}
                        """));

        Map<String, Object> updates = node.apply(stateOf("写一个 Java 快排"));

        WorkflowState.QueryPlan plan = (WorkflowState.QueryPlan) updates.get(WorkflowState.QUERY_PLAN);
        assertThat(updates.get(WorkflowState.INTENT)).isEqualTo(QaIntent.CODE_GENERATE);
        assertThat(plan.requiresFullRetrieval()).isFalse();
    }

    @Test
    @DisplayName("LLM 不可用 → 降级为知识问答 + 单查询，不阻断主链路")
    void degradeWhenLlmUnavailable() {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        Map<String, Object> updates = node.apply(stateOf("jvm 内存模型"));

        WorkflowState.QueryPlan plan = (WorkflowState.QueryPlan) updates.get(WorkflowState.QUERY_PLAN);
        assertThat(updates.get(WorkflowState.INTENT)).isEqualTo(QaIntent.DEFAULT);
        assertThat(plan.primaryQuery()).isEqualTo("jvm 内存模型");
        assertThat(plan.subQueries()).containsExactly("jvm 内存模型");
        assertThat(plan.requiresFullRetrieval()).isFalse();
    }

    @Test
    @DisplayName("意图提示词 → 概念题不因出现技术名词被归为 code_generate")
    void intentPromptSeparatesConceptFromCodeGeneration() {
        assertThat(PromptTemplates.INTENT_PLAN_SYSTEM)
                .contains("equals 和 == 有什么区别")
                .contains("仅出现技术名词、类名、方法名或语言名不构成 code_generate")
                .contains("无写/改/补全/调试动作一律归 knowledge_qa");
    }

    @Test
    @DisplayName("未知意图与空子查询 → 白名单归一 + 回退主查询")
    void normalizeUnknownIntentAndEmptySubQueries() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("""
                        {"intent":"unknown","primaryQuery":"JVM 是什么","subQueries":[],
                         "complexity":"","risk":"unknown","reason":""}
                        """));

        Map<String, Object> updates = node.apply(stateOf("JVM 是什么"));

        WorkflowState.QueryPlan plan = (WorkflowState.QueryPlan) updates.get(WorkflowState.QUERY_PLAN);
        assertThat(updates.get(WorkflowState.INTENT)).isEqualTo(QaIntent.DEFAULT);
        assertThat(plan.subQueries()).containsExactly("JVM 是什么");
        assertThat(plan.risk()).isEqualTo("low");
    }

    private WorkflowState stateOf(String query) {
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, query,
                WorkflowState.MODEL, "test-model"));
    }
}
