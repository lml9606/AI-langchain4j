package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.guard.OutputGuardService;
import com.example.langchain4j.graph.qa.AnswerBudgetResolver;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.LlmResult;
import com.example.langchain4j.repository.mapper.PromptConfigMapper;
import com.example.langchain4j.service.PromptTemplateService;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 生成节点单测：动态长度约束必须进入 system prompt，预算 token 必须传给 LLM。 */
@ExtendWith(MockitoExtension.class)
class GenerateNodeTest {

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private OutputGuardService outputGuardService;
    @Mock
    private PromptConfigMapper promptConfigMapper;

    private GenerateNode node;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        PromptTemplateService promptTemplateService = new PromptTemplateService(promptConfigMapper);
        node = new GenerateNode(llmGateway, outputGuardService, promptTemplateService,
                new AnswerBudgetResolver(properties));
    }

    @Test
    @DisplayName("简单知识问答 → system prompt 注入 500 字约束，LLM 收到 1024 token 上限")
    void simpleKnowledgeUsesDynamicBudget() {
        when(llmGateway.generate(any(LlmRequest.class)))
                .thenReturn(new LlmResult("结论", 20, FinishReason.STOP));

        node.apply(stateOf("simple", 1, "auto"));

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).generate(requestCaptor.capture());
        LlmRequest request = requestCaptor.getValue();
        assertThat(request.model()).isEqualTo("test-model");
        assertThat(request.maxOutputTokens()).isEqualTo(1024);
        SystemMessage system = (SystemMessage) request.messages().get(0);
        assertThat(system.text())
                .contains("总长度控制在 500 字以内")
                .contains("最多 3 条关键点");
    }

    @Test
    @DisplayName("复合知识问答 → LLM 收到复合预算 1536 token")
    void compoundKnowledgeUsesLargerBudget() {
        when(llmGateway.generate(any(LlmRequest.class)))
                .thenReturn(new LlmResult("结论", 20, FinishReason.STOP));

        node.apply(stateOf("compound", 2, "auto"));

        ArgumentCaptor<LlmRequest> requestCaptor = ArgumentCaptor.forClass(LlmRequest.class);
        verify(llmGateway).generate(requestCaptor.capture());
        assertThat(requestCaptor.getValue().maxOutputTokens()).isEqualTo(1536);
    }

    private WorkflowState stateOf(String complexity, int subQueryCount, String answerMode) {
        List<String> subQueries = java.util.stream.IntStream.range(0, subQueryCount)
                .mapToObj(i -> "子问题" + (i + 1))
                .toList();
        WorkflowState.QueryPlan plan = new WorkflowState.QueryPlan(
                "主查询", subQueries, complexity, "low", subQueryCount > 1, "test");
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, "主查询",
                WorkflowState.MODEL, "test-model",
                WorkflowState.INTENT, QaIntent.KNOWLEDGE_QA,
                WorkflowState.QUERY_PLAN, plan,
                WorkflowState.ANSWER_MODE, answerMode,
                WorkflowState.CONTEXT, "【参考资料 1】测试资料"));
    }
}
