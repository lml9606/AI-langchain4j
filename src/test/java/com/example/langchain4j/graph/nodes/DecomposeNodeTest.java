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

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 问题分解节点单测：分解失败降级单查询与白名单数量截断是防御核心。
 */
@ExtendWith(MockitoExtension.class)
class DecomposeNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private AgentProperties properties;
    @Mock
    private PromptTemplateService promptTemplateService;

    private DecomposeNode node;

    @BeforeEach
    void setUp() {
        node = new DecomposeNode(llmGateway, properties, promptTemplateService);
        // 模板兑底语义：返回默认模板并做参数格式化（与 PromptTemplateService.resolveFormatted 真实行为一致）
        // 注意：显式 new Object[] 包装，避免编译器把 getArgument(2) 推断为 varargs 数组导致 Integer→Object[] 强转失败
        when(promptTemplateService.resolveFormatted(anyString(), anyString(), any()))
                .thenAnswer(invocation -> String.format(
                        invocation.getArgument(1), new Object[]{invocation.getArgument(2)}));
        when(properties.getMaxSubQueries()).thenReturn(3);
    }

    private WorkflowState stateOf(String query) {
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, query,
                WorkflowState.MODEL, "test-model"));
    }

    @Test
    @DisplayName("LLM 失败 → 降级为单查询（等同未分解，不阻断主流程）")
    void degradeWhenLlmFails() {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(null);

        Map<String, Object> updates = node.apply(stateOf("A 公司和 B 公司的规则"));

        assertThat(updates.get(WorkflowState.SUB_QUERIES)).isEqualTo(List.of("A 公司和 B 公司的规则"));
    }

    @Test
    @DisplayName("空子查询数组 → 降级为单查询")
    void degradeWhenEmptySubQueries() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"subQueries\":[]}"));

        Map<String, Object> updates = node.apply(stateOf("A 公司和 B 公司的规则"));

        assertThat(updates.get(WorkflowState.SUB_QUERIES)).isEqualTo(List.of("A 公司和 B 公司的规则"));
    }

    @Test
    @DisplayName("正常分解 → 写入子查询列表（供首轮遍历检索）")
    void decomposeIntoSeparateQueries() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"subQueries\":[\"A 公司的规则\",\"B 公司的规则\"]}"));

        Map<String, Object> updates = node.apply(stateOf("A 公司和 B 公司的规则"));

        assertThat(updates.get(WorkflowState.SUB_QUERIES))
                .isEqualTo(List.of("A 公司的规则", "B 公司的规则"));
    }

    @Test
    @DisplayName("子查询数量超上限 → 白名单截断只取前 N 个")
    void truncateWhenTooManySubQueries() throws Exception {
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"subQueries\":[\"一\",\"二\",\"三\",\"四\",\"五\"]}"));

        Map<String, Object> updates = node.apply(stateOf("复杂问题"));

        assertThat(updates.get(WorkflowState.SUB_QUERIES)).isEqualTo(List.of("一", "二", "三"));
    }
}
