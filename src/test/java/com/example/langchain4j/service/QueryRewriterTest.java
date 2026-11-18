package com.example.langchain4j.service;

import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.service.model.RewriteResult;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 查询改写单测：无历史短路、指代消解与失败降级。 */
@ExtendWith(MockitoExtension.class)
class QueryRewriterTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private PromptTemplateService promptTemplateService;

    private QueryRewriter rewriter;

    @BeforeEach
    void setUp() {
        rewriter = new QueryRewriter(llmGateway, promptTemplateService);
    }

    @Test
    @DisplayName("无历史 → 不做无意义的改写调用，原句直接返回")
    void skipLlmWhenNoHistory() {
        RewriteResult result = rewriter.rewrite("jvm", List.of(), List.of(), "test-model");

        assertThat(result.rewritten()).isEqualTo("jvm");
        verifyNoInteractions(llmGateway);
    }

    @Test
    @DisplayName("含指代词 → 走 LLM 改写")
    void rewriteFollowUpQuery() throws Exception {
        when(promptTemplateService.resolve(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"rewritten\":\"JVM 是什么\",\"topic\":\"JVM\",\"newTopic\":false}"));

        RewriteResult result = rewriter.rewrite("它是什么", history(), List.of("JVM"), "test-model");

        assertThat(result.rewritten()).isEqualTo("JVM 是什么");
    }

    @Test
    @DisplayName("短的新话题 → 仍走 LLM 做话题归属，避免误归上一段")
    void keepLlmForNewShortTopic() throws Exception {
        when(promptTemplateService.resolve(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"rewritten\":\"Redis 是什么\",\"topic\":\"Redis\",\"newTopic\":true}"));

        RewriteResult result = rewriter.rewrite("Redis", history(), List.of("JVM"), "test-model");

        assertThat(result.rewritten()).isEqualTo("Redis 是什么");
        assertThat(result.newTopic()).isTrue();
    }

    @Test
    @DisplayName("详细说说 → 原样返回时补上一轮问题锚点，并强制沿用上一话题")
    void repairUnresolvedDetailFollowUp() throws Exception {
        when(promptTemplateService.resolve(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        when(llmGateway.generateJsonNode(anyString(), anyString(), anyString(), anyString()))
                .thenReturn(MAPPER.readTree("{\"rewritten\":\"详细说说\",\"topic\":\"\",\"newTopic\":true}"));

        RewriteResult result = rewriter.rewrite("详细说说", List.of(
                Map.of("role", "user", "content", "equals 和 == 有什么区别"),
                Map.of("role", "assistant", "content", "== 比较地址，equals 重写后比较内容")),
                List.of("equals"), "test-model");

        assertThat(result.rewritten()).isEqualTo("equals 和 == 有什么区别，详细说说");
        assertThat(result.newTopic()).isFalse();
    }

    private List<Map<String, String>> history() {
        return List.of(Map.of("role", "user", "content", "JVM 是什么"));
    }
}
