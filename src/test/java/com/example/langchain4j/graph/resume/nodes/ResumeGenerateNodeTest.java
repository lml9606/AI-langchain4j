package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.LlmResult;
import com.example.langchain4j.repository.mapper.PromptConfigMapper;
import com.example.langchain4j.service.PromptTemplateService;
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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 生成节点单测：正常 JSON、截断后换更大预算重试、两次都不可用则写 error。
 */
@ExtendWith(MockitoExtension.class)
class ResumeGenerateNodeTest {

    private static final String VALID_JSON = """
            {"revisedMarkdown":"## 工作经历\\nJava 开发，负责订单系统",
             "changes":[{"section":"工作经历","type":"rewrote","summary":"精简","reason":"对齐 JD"}]}
            """;

    @Mock
    private LlmGateway llmGateway;

    private ResumeGenerateNode node;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getResume().setMaxOutputTokens(8_192);
        properties.getResume().setRetryMaxOutputTokens(12_288);
        PromptTemplateService promptTemplateService =
                new PromptTemplateService(mock(PromptConfigMapper.class));
        node = new ResumeGenerateNode(llmGateway, promptTemplateService, properties);
    }

    @Test
    @DisplayName("正常 JSON → 写 revisedMd/changes，无 error")
    void success() {
        when(llmGateway.generateJson(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new LlmResult(VALID_JSON, 120, FinishReason.STOP));

        Map<String, Object> updates = node.apply(state());

        assertThat((String) updates.get(ResumeOptimizeState.REVISED_MD))
                .contains("Java 开发");
        assertThat((List<?>) updates.get(ResumeOptimizeState.CHANGES)).hasSize(1);
        assertThat(updates.get(ResumeOptimizeState.ERROR)).isEqualTo("");
    }

    @Test
    @DisplayName("首次输出被截断 → 用更大预算重试并成功")
    void retryWithLargerBudgetWhenTruncated() {
        when(llmGateway.generateJson(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new LlmResult("partial", 50, FinishReason.LENGTH))
                .thenReturn(new LlmResult(VALID_JSON, 100, FinishReason.STOP));

        Map<String, Object> updates = node.apply(state());

        ArgumentCaptor<Integer> budgets = ArgumentCaptor.forClass(Integer.class);
        verify(llmGateway, times(2)).generateJson(anyString(), anyString(), anyString(), budgets.capture());
        assertThat(budgets.getAllValues()).containsExactly(8_192, 12_288);
        assertThat((String) updates.get(ResumeOptimizeState.REVISED_MD)).contains("Java 开发");
    }

    @Test
    @DisplayName("两次输出都不是合法 JSON → 写 error，不返回半份改稿")
    void failWhenJsonUnusable() {
        when(llmGateway.generateJson(anyString(), anyString(), anyString(), anyInt()))
                .thenReturn(new LlmResult("not-json", 10, FinishReason.STOP));

        Map<String, Object> updates = node.apply(state());

        assertThat((String) updates.get(ResumeOptimizeState.ERROR)).contains("合法 JSON");
        assertThat(updates).doesNotContainKey(ResumeOptimizeState.REVISED_MD);
    }

    private ResumeOptimizeState state() {
        return new ResumeOptimizeState(Map.of(
                ResumeOptimizeState.RESUME_MD, "## 工作经历\nJava 开发",
                ResumeOptimizeState.MODE, "TARGETED",
                ResumeOptimizeState.TARGET_BLOCK, "### 目标岗位 1\n- 岗位：Java 后端",
                ResumeOptimizeState.MODEL, "test-model",
                ResumeOptimizeState.RETRY_COUNT, 0,
                ResumeOptimizeState.INPUT_TRUNCATED, false));
    }
}
