package com.example.langchain4j.llm;

import com.example.langchain4j.dto.request.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** LlmGateway 门面单测：文本/JSON 分流、输出预算透传、截断和解析失败降级。 */
@ExtendWith(MockitoExtension.class)
class LlmGatewayTest {

    @Mock
    private LlmInvoker llmInvoker;

    private LlmGateway gateway;

    @BeforeEach
    void setUp() {
        gateway = new LlmGateway(llmInvoker);
    }

    @Test
    @DisplayName("文本生成 → 不设置 JSON 格式，模型与输出预算原样透传")
    void generateTextPassesRequestThrough() {
        LlmRequest request = LlmRequest.of("model-a",
                        List.of(UserMessage.from("hello")))
                .scene("generate")
                .maxOutputTokens(512);
        when(llmInvoker.invoke(eq("model-a"), anyList(), isNull(), eq(512)))
                .thenReturn(LlmResult.of("ok", 10, FinishReason.STOP));

        LlmResult result = gateway.generate(request);

        assertThat(result.text()).isEqualTo("ok");
        verify(llmInvoker).invoke(eq("model-a"), anyList(), isNull(), eq(512));
    }

    @Test
    @DisplayName("JSON 生成 → 使用 JSON 格式并解析出 JsonNode")
    void generateJsonParsesStructuredOutput() {
        LlmRequest request = LlmRequest.of("model-a",
                        "system", "user")
                .scene("intent-plan")
                .maxOutputTokens(1024);
        when(llmInvoker.invoke(eq("model-a"), anyList(), eq(ResponseFormat.JSON), eq(1024)))
                .thenReturn(LlmResult.of("{\"intent\":\"knowledge_qa\"}", 10, FinishReason.STOP));

        JsonNode node = gateway.generateJson(request);

        assertThat(node).isNotNull();
        assertThat(node.path("intent").asText()).isEqualTo("knowledge_qa");
    }

    @Test
    @DisplayName("输出被截断 → 直接返回 null，不解析残缺 JSON")
    void truncatedJsonReturnsNull() {
        LlmRequest request = LlmRequest.of("model-a", "system", "user").scene("intent-plan");
        when(llmInvoker.invoke(eq("model-a"), anyList(), eq(ResponseFormat.JSON), isNull()))
                .thenReturn(LlmResult.of("{\"intent\":", 10, FinishReason.LENGTH));

        assertThat(gateway.generateJson(request)).isNull();
    }

    @Test
    @DisplayName("非 JSON 输出 → 解析失败返回 null，由调用方降级")
    void invalidJsonReturnsNull() {
        LlmRequest request = LlmRequest.of("model-a", "system", "user").scene("intent-plan");
        when(llmInvoker.invoke(eq("model-a"), anyList(), eq(ResponseFormat.JSON), isNull()))
                .thenReturn(LlmResult.of("not-json", 10, FinishReason.STOP));

        assertThat(gateway.generateJson(request)).isNull();
    }
}
