package com.example.langchain4j.tool.provider;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 只读业务查询工具：只支持白名单命名查询，不接受任意 SQL。
 * 所有查询都带 owner_user_id 过滤，防止水平越权。
 */
@Component
public class SqlQueryTool implements AgentTool {

    private static final String QUERY_ORDER = "order_status";
    private static final String QUERY_REFUND = "refund_record";
    private static final int MAX_SNIPPET = 300;

    private final JdbcTemplate jdbcTemplate;

    public SqlQueryTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "query_business_data",
                "查询只读业务数据：订单状态或退款记录",
                "{\"type\":\"object\",\"required\":[\"queryName\",\"orderId\"],\"properties\":{"
                        + "\"queryName\":{\"type\":\"string\",\"enum\":[\"order_status\",\"refund_record\"]},"
                        + "\"orderId\":{\"type\":\"string\"}}}",
                List.of("queryName", "orderId"),
                ToolSideEffect.READ_ONLY,
                3000);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String queryName = args.path("queryName").asText("").trim();
        String orderId = args.path("orderId").asText("").trim();
        if (context.userId().isBlank()) {
            return ToolResult.failure("NO_USER", "缺少用户身份，拒绝查询业务数据",
                    System.currentTimeMillis() - start);
        }
        String sql;
        if (QUERY_ORDER.equals(queryName)) {
            sql = "SELECT order_id, status, amount, paid_at FROM ai_demo_order "
                    + "WHERE order_id = ? AND owner_user_id = ?";
        } else if (QUERY_REFUND.equals(queryName)) {
            sql = "SELECT refund_id, order_id, status, fail_reason, created_at FROM ai_demo_refund "
                    + "WHERE order_id = ? AND owner_user_id = ?";
        } else {
            return ToolResult.failure("INVALID_ARGS", "不支持的查询: " + queryName,
                    System.currentTimeMillis() - start);
        }
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(sql, orderId, context.userId());
        List<ToolEvidence> evidence = new ArrayList<>();
        for (int i = 0; i < Math.min(rows.size(), 3); i++) {
            Map<String, Object> row = rows.get(i);
            evidence.add(new ToolEvidence("TOOL", orderId,
                    QUERY_ORDER.equals(queryName) ? "订单状态" : "退款记录",
                    truncate(JsonUtil.toJson(row), MAX_SNIPPET), 0.0));
        }
        String summary = rows.isEmpty()
                ? "未查询到 " + queryName + " 记录，orderId=" + orderId
                : queryName + " 查询结果: " + JsonUtil.toJson(rows);
        return ToolResult.success(rows, summary,
                evidence, System.currentTimeMillis() - start);
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
