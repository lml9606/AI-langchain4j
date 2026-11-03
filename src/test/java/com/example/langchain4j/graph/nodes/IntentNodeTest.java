package com.example.langchain4j.graph.nodes;

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
import static org.mockito.Mockito.when;

/**
 * 意图识别节点单测：LLM 失败/异常输出时的降级路径是防御核心（不阻断主流程）。
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
        // 配置化解析默认兑底：测试聚焦节点逻辑，resolve 一律返回代码默认值
        when(promptTemplateService.resolve(anyString(), anyString()))
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
    @DisplayName("LLM 输出截断/解析失败 → 降级默认意图，不阻断主流程")
    void degradeWhenLlmReturnsNull() {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        Map<String, Object> updates = node.apply(stateOf("你好"));

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
