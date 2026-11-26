package com.example.langchain4j.eval;

import com.example.langchain4j.common.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * 评测执行器的 HTTP 客户端：负责登录、带 Token 调检索接口、解析结果。
 *
 * 执行器不调用 LLM，也不做对错判断；它只把真实接口的返回原样带回来，
 * 保证同一份评测集在不同时间跑出的结果可比较。
 */
final class EvalHttpClient {

    private final EvalConfig config;
    private final HttpClient client;
    private String token;

    /** 装配基础地址和超时；构造阶段不发请求，避免类加载时依赖环境可用。 */
    EvalHttpClient(EvalConfig config) {
        this.config = config;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    /**
     * 探活：确认评测环境已经启动。
     *
     * 只访问 JWT 白名单内的 /actuator/health，不消耗业务接口配额；服务没起来时提前失败，
     * 避免把整批样本刷成 RUNTIME_ERROR 后还要逐条排查。
     */
    void healthCheck() {
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/actuator/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("评测环境未就绪: /actuator/health HTTP "
                    + response.statusCode() + "，body=" + abbreviate(response.body()));
        }
        if (response.body() == null || !response.body().contains("\"UP\"")) {
            throw new IllegalStateException("评测环境状态异常: " + abbreviate(response.body()));
        }
    }

    /**
     * 调父子装配检索接口，评测实际可供 RAG 生成使用的完整上下文。
     *
     * @return 命中块内容 + 请求耗时；HTTP 非 200 或业务 code 非 0 时抛出
     */
    SearchHit search(String query, int topK) {
        String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
        URI uri = URI.create(config.baseUrl() + "/api/knowledge/search-context?query="
                + encoded + "&topK=" + topK);
        long start = System.currentTimeMillis();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + token())
                .GET()
                .build();
        HttpResponse<String> response = send(request);
        long cost = System.currentTimeMillis() - start;
        if (response.statusCode() != 200) {
            throw new IllegalStateException("检索接口 HTTP " + response.statusCode()
                    + "，body=" + abbreviate(response.body()));
        }
        JsonNode root = parse(response.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("检索接口业务失败: code="
                    + root.path("code").asInt(-1) + ", message=" + root.path("message").asText(""));
        }
        List<String> contents = new ArrayList<>();
        for (JsonNode chunk : root.path("data").path("chunks")) {
            String content = chunk.path("content").asText("");
            if (!content.isBlank()) {
                contents.add(content);
            }
        }
        return new SearchHit(contents, cost);
    }

    /** Token 优先用显式配置；没有则用用户名密码登录换取，避免脚本里手贴 JWT。 */
    private String token() {
        if (token != null && !token.isBlank()) {
            return token;
        }
        if (config.token() != null && !config.token().isBlank()) {
            token = config.token();
            return token;
        }
        if (config.username() == null || config.username().isBlank()
                || config.password() == null || config.password().isBlank()) {
            throw new IllegalStateException("缺少 EVAL_TOKEN 或 EVAL_USERNAME/EVAL_PASSWORD，无法登录评测环境");
        }
        String body = JsonUtil.toJson(new LoginBody(config.username(), config.password()));
        HttpRequest request = HttpRequest.newBuilder(URI.create(config.baseUrl() + "/api/auth/login"))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> response = send(request);
        if (response.statusCode() != 200) {
            throw new IllegalStateException("登录失败 HTTP " + response.statusCode()
                    + "，body=" + abbreviate(response.body()));
        }
        JsonNode root = parse(response.body());
        if (root.path("code").asInt(-1) != 0) {
            throw new IllegalStateException("登录失败: " + root.path("message").asText(""));
        }
        token = root.path("data").path("token").asText("");
        if (token.isBlank()) {
            throw new IllegalStateException("登录响应未返回 token");
        }
        return token;
    }

    /** 统一发送并把网络异常转成带上下文的运行时异常，方便 CI 直接看到原因。 */
    private HttpResponse<String> send(HttpRequest request) {
        try {
            return client.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("请求评测环境失败: " + request.uri() + "，原因=" + e.getMessage(), e);
        }
    }

    /** 解析 JSON；非 JSON 响应附带原文片段，便于定位网关/代理错误。 */
    private JsonNode parse(String body) {
        try {
            return JsonUtil.mapper().readTree(body);
        } catch (Exception e) {
            throw new IllegalStateException("响应不是合法 JSON，body=" + abbreviate(body), e);
        }
    }

    /** 日志截断：错误响应可能很长，只保留前 300 字。 */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 300 ? text : text.substring(0, 300) + "...[截断]";
    }

    /** 一次检索的原始结果：命中内容 + 耗时，由执行器负责计算覆盖率。 */
    record SearchHit(List<String> contents, long latencyMs) {
    }

    /** 登录请求体；字段名与 /api/auth/login 的 LoginRequest 保持一致。 */
    private record LoginBody(String username, String password) {
    }
}
