package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.service.PromptTemplateService;
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
 * 多跳判断节点单测：三种走向（足够放行 / 达上限标缺失 / 未达上限下一跳）与 fail-open 防御。
 */
@ExtendWith(MockitoExtension.class)
class HopCheckNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private AgentProperties properties;
    @Mock
    private PromptTemplateService promptTemplateService;

    private HopCheckNode node;

    @BeforeEach
    void setUp() {
        node = new HopCheckNode(llmGateway, properties, promptTemplateService);
        when(promptTemplateService.resolve(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private WorkflowState stateOf(int hopCount, String pendingQuery) {
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, "问题",
                WorkflowState.CONTEXT, "资料",
                WorkflowState.HOP, WorkflowState.Hop.of(hopCount, pendingQuery, false)));
    }

    private WorkflowState.Hop hopOf(Map<String, Object> updates) {
        return (WorkflowState.Hop) updates.get(WorkflowState.HOP);
    }

    @Test
    @DisplayName("上下文足够 → 放行去生成，下一跳清空")
    void passWhenSufficient() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"sufficient\":true}"));

        Map<String, Object> updates = node.apply(stateOf(0, ""));

        WorkflowState.Hop hop = hopOf(updates);
        assertThat(hop.sufficient()).isTrue();
        assertThat(hop.pendingQuery()).isEmpty();
        // 首轮即足够：不写根因
        assertThat(updates).doesNotContainKey(WorkflowState.ROOT_CAUSE);
    }

    @Test
    @DisplayName("不足且已达多跳上限 → 标记上下文不完整 + 知识缺失根因")
    void markMissingWhenMaxHopsReached() throws Exception {
        when(properties.getMaxHops()).thenReturn(1);
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"sufficient\":false,\"nextQuery\":\"再查一次\"}"));

        Map<String, Object> updates = node.apply(stateOf(1, ""));

        assertThat(updates.get(WorkflowState.CONTEXT_INCOMPLETE)).isEqualTo(true);
        assertThat(updates.get(WorkflowState.ROOT_CAUSE)).isEqualTo(WorkflowState.ROOT_CAUSE_KNOWLEDGE_MISSING);
        assertThat(hopOf(updates).sufficient()).isTrue();
    }

    @Test
    @DisplayName("不足且未达上限 → 写入下一跳查询，计数 +1 路由回检索")
    void nextHopWhenInsufficient() throws Exception {
        when(properties.getMaxHops()).thenReturn(3);
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"sufficient\":false,\"nextQuery\":\"细化后的查询\"}"));

        Map<String, Object> updates = node.apply(stateOf(0, ""));

        WorkflowState.Hop hop = hopOf(updates);
        assertThat(hop.count()).isEqualTo(1);
        assertThat(hop.pendingQuery()).isEqualTo("细化后的查询");
        assertThat(hop.sufficient()).isFalse();
    }

    @Test
    @DisplayName("判断 LLM 不可用 → fail-open 视为足够（漏判优于误杀）")
    void failOpenWhenJudgeUnavailable() {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        assertThat(hopOf(node.apply(stateOf(0, ""))).sufficient()).isTrue();
    }
}
