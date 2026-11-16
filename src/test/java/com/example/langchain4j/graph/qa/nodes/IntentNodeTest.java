package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.service.PromptTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 意图识别节点单测：寒暄规则快速通道（零 LLM 调用）与 LLM 失败/异常输出时的降级路径是防御核心。
 */
@ExtendWith(MockitoExtension.class)
class IntentNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private PromptTemplateService promptTemplateService;

    private IntentNode node;

    @BeforeEach
    void setUp() {
        node = new IntentNode(llmGateway, promptTemplateService);
        // 配置化解析默认兑底：测试聚焦节点逻辑，resolve 一律返回代码默认值；
        // lenient：规则快速通道测试不调 resolve，该 stub 在其场景下非必需
        lenient().when(promptTemplateService.resolve(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private WorkflowState stateOf(String query) {
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, query,
                WorkflowState.MODEL, "test-model"));
    }

    private JsonNode json(String content) throws Exception {
        return MAPPER.readTree(content);
    }

    @Test
    @DisplayName("纯寒暄短句 → 规则快速通道命中闲聊，不调用 LLM")
    void chitchatByRuleWithoutLlm() {
        Map<String, Object> updates = node.apply(stateOf("你好"));

        assertThat(updates.get(WorkflowState.INTENT)).isEqualTo(IntentNode.INTENT_CHITCHAT);
        assertThat(updates.get(WorkflowState.INTENT_REASON)).isEqualTo("规则匹配：寒暄");
        assertThat(updates.get(WorkflowState.ERROR)).isEqualTo("");
        // 规则命中不得触碰 LLM（零成本快速通道）
        verify(llmGateway, never()).generateJsonNode(anyString(), anyString(), anyString(), anyString());
    }

    @Test
    @DisplayName("寒暄+业务诉求的长句 → 规则不命中，交 LLM 分类")
    void mixedSentenceNotMatchedByRule() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(json("{\"intent\":\"knowledge_qa\",\"reason\":\"带业务诉求\"}"));

        Map<String, Object> updates = node.apply(stateOf("你好，帮我查一下退款政策"));

        assertThat(updates.get(WorkflowState.INTENT)).isEqualTo(IntentNode.INTENT_KNOWLEDGE_QA);
    }

    @Test
    @DisplayName("LLM 输出截断/解析失败 → 降级默认意图，不阻断主流程")
    void degradeWhenLlmReturnsNull() {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        Map<String, Object> updates = node.apply(stateOf("今天的天气怎么样"));

        assertThat(updates.get(WorkflowState.INTENT)).isEqualTo(IntentNode.INTENT_DEFAULT);
        assertThat(updates.get(WorkflowState.INTENT_REASON)).isEqualTo("输出截断或解析失败");
        assertThat(updates.get(WorkflowState.ERROR)).isEqualTo("");
    }

    @Test
    @DisplayName("模型输出未知意图 → 白名单校验降级默认意图")
    void degradeWhenUnknownIntent() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(json("{\"intent\":\"unknown_intent\",\"reason\":\"无法判断\"}"));

        Map<String, Object> updates = node.apply(stateOf("今天天气怎么样"));

        assertThat(updates.get(WorkflowState.INTENT)).isEqualTo(IntentNode.INTENT_DEFAULT);
    }

    @Test
    @DisplayName("正常识别代码生成意图 → 写入意图与理由")
    void recognizeCodeIntent() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(json("{\"intent\":\"code_generate\",\"reason\":\"用户要求生成代码\"}"));

        Map<String, Object> updates = node.apply(stateOf("写一个排序函数"));

        assertThat(updates.get(WorkflowState.INTENT)).isEqualTo(IntentNode.INTENT_CODE_GENERATE);
        assertThat(updates.get(WorkflowState.INTENT_REASON)).isEqualTo("用户要求生成代码");
    }
}
