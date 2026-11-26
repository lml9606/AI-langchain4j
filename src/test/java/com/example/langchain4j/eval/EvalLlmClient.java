package com.example.langchain4j.eval;

import com.example.langchain4j.common.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * 评测用轻量 LLM 客户端：只服务出题和验收两个 Agent，不与业务 LlmGateway 耦合。
 *
 * 使用 OpenAI 兼容的 /chat/completions 协议；没有 API Key 时调用方应跳过 LLM 阶段，
 * 改用确定性规则，保证流水线在纯 CI 环境也能跑。
 */
final class EvalLlmClient {

    private final EvalConfig config;
    private final HttpClient client;

    /** 只保存配置和 HTTP 客户端，不在构造阶段发请求。 */
    EvalLlmClient(EvalConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 单轮对话：system + user，返回纯文本。
     *
     * @return 模型输出；调用失败时抛出，由调用方决定跳过还是让流水线失败
     */
    String chat(String systemPrompt, String userPrompt) {
        if (!config.llmAvailable()) {
            throw new IllegalStateException("未配置 EVAL_LLM_API_KEY / DEEPSEEK_API_KEY");
        }
        Map<String, Object> body = Map.of(
                "model", config.llmModel(),
                "messages", List.of(
                        Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt)),
                "temperature", 0.2,
                "max_tokens", 1200,
                "stream", false);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint()))
                .timeout(Duration.ofSeconds(90))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + config.llmApiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                        JsonUtil.toJson(body), StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("评测 LLM 请求失败: " + e.getMessage(), e);
        }
        if (response.statusCode() != 200) {
            throw new IllegalStateException("评测 LLM HTTP " + response.statusCode()
                    + "，body=" + abbreviate(response.body()));
        }
        try {
            JsonNode root = JsonUtil.mapper().readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText("");
            if (content.isBlank()) {
                throw new IllegalStateException("评测 LLM 返回空内容");
            }
            return content;
        } catch (Exception e) {
            throw new IllegalStateException("评测 LLM 响应解析失败: " + e.getMessage(), e);
        }
    }

    /** 兼容 baseUrl 末尾带/不带斜杠，以及已经包含 /v1 的配置。 */
    private String endpoint() {
        String base = config.llmBaseUrl();
        if (base.endsWith("/chat/completions")) {
            return base;
        }
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return base + "/chat/completions";
    }

    /** 错误响应截断，避免日志被网关 HTML 或超长报文刷屏。 */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(0, 500) + "...[截断]";
    }
}
