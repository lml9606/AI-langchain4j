package com.example.langchain4j.tool.provider;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * HTTP 只读查询工具：演示把外部 GET 接口包装成工具。
 * 当前指向本地 mock 接口，生产可替换为真实业务 API。
 */
@Component
public class HttpOrderTool implements AgentTool {

    private final AgentProperties properties;

    public HttpOrderTool(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "query_order_api",
                "通过 HTTP 接口查询订单详情",
                "{\"type\":\"object\",\"required\":[\"orderId\"],\"properties\":{"
                        + "\"orderId\":{\"type\":\"string\"}}}",
                List.of("orderId"),
                ToolSideEffect.READ_ONLY,
                3000);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) throws Exception {
        long start = System.currentTimeMillis();
        String orderId = args.path("orderId").asText("").trim();
        AgentProperties.Tool.Http cfg = properties.getTool().getHttp();
        if (cfg.getBaseUrl() == null || cfg.getBaseUrl().isBlank()) {
            return ToolResult.failure("CONFIG", "HTTP 工具未配置 base-url",
                    System.currentTimeMillis() - start);
        }
        String base = cfg.getBaseUrl().endsWith("/")
                ? cfg.getBaseUrl().substring(0, cfg.getBaseUrl().length() - 1)
                : cfg.getBaseUrl();
        URI uri = URI.create(base + "/mock/orders/"
                + URLEncoder.encode(orderId, StandardCharsets.UTF_8));
        if (!hostAllowed(uri, cfg.getAllowedHosts())) {
            return ToolResult.failure("FORBIDDEN_HOST", "HTTP 工具目标域名不在白名单: " + uri.getHost(),
                    System.currentTimeMillis() - start);
        }
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(cfg.getTimeoutMs()))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMillis(cfg.getTimeoutMs()))
                .header("X-User-Id", context.userId())
                .GET()
                .build();
        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
        long cost = System.currentTimeMillis() - start;
        if (response.statusCode() != 200) {
            return ToolResult.failure("HTTP_" + response.statusCode(),
                    "HTTP 查询失败: " + response.body(), cost);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> body = JsonUtil.fromJson(response.body(), Map.class);
        String summary = "HTTP 订单查询结果: " + response.body();
        List<ToolEvidence> evidence = List.of(new ToolEvidence("TOOL", orderId,
                "订单 HTTP 查询", truncate(response.body(), 300), 0.0));
        return ToolResult.success(body, summary, evidence, cost);
    }

    private boolean hostAllowed(URI uri, List<String> allowedHosts) {
        if (uri.getHost() == null || allowedHosts == null || allowedHosts.isEmpty()) {
            return false;
        }
        return allowedHosts.stream().anyMatch(h -> h.equalsIgnoreCase(uri.getHost()));
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
