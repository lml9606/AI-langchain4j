package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.guard.OutputGuardService;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.LlmResult;
import com.example.langchain4j.service.PromptTemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 任务回答节点单测：证据引用追加、置信度可解释、模型失败降级为确定性摘要。
 */
@ExtendWith(MockitoExtension.class)
class TaskAnswerNodeTest {

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private OutputGuardService outputGuardService;
    @Mock
    private PromptTemplateService promptTemplateService;

    private TaskAnswerNode node;

    @BeforeEach
    void setUp() {
        node = new TaskAnswerNode(llmGateway, outputGuardService, promptTemplateService);
        lenient().when(promptTemplateService.resolve(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(outputGuardService.blocked(anyString())).thenReturn(false);
    }

    private WorkflowState stateWithEvidence() {
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, "帮我查业务对象 B1001",
                WorkflowState.MODEL, "test-model",
                WorkflowState.TASK_TRACE, List.of(
                        Map.of("tool", "read_tool",
                                "args", Map.of("entityId", "B1001"),
                                "success", true,
                                "summary", "业务对象 B1001 状态 PAID",
                                "errorCode", "",
                                "costMs", 12)),
                WorkflowState.TASK_EVIDENCE, List.of(
                        Map.of("type", "TOOL",
                                "sourceId", "B1001",
                                "title", "业务对象状态",
                                "snippet", "{\"status\":\"PAID\"}",
                                "score", 0.0))));
    }

    @Test
    @DisplayName("正常回答 → 追加参考依据，置信度与理由可解释")
    void appendCitationsAndConfidence() {
        when(llmGateway.generate(anyString(), anyString(), anyString()))
                .thenReturn(LlmResult.of("业务对象 B1001 已支付。", 10, null));

        Map<String, Object> updates = node.apply(stateWithEvidence());

        assertThat(String.valueOf(updates.get(WorkflowState.RESULT)))
                .contains("业务对象 B1001 已支付")
                .contains("**参考依据**")
                .contains("B1001");
        assertThat((Double) updates.get(WorkflowState.TASK_CONFIDENCE)).isGreaterThan(0.5);
        assertThat(String.valueOf(updates.get(WorkflowState.TASK_CONFIDENCE_REASON)))
                .contains("1 条工具证据");
    }

    @Test
    @DisplayName("模型失败 → 确定性摘要兜底，不抛异常")
    void deterministicFallbackWhenLlmFails() {
        when(llmGateway.generate(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("llm down"));

        Map<String, Object> updates = node.apply(stateWithEvidence());

        assertThat(String.valueOf(updates.get(WorkflowState.RESULT)))
                .contains("关键结果")
                .contains("PAID");
        assertThat(updates.get(WorkflowState.ERROR)).isEqualTo("");
    }
}
