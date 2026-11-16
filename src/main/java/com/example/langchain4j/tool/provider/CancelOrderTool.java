package com.example.langchain4j.tool.provider;

import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 取消订单写工具：只允许取消本人名下、状态为 PAID 的订单。
 * <p>
 * 执行层仍会校验 approved 与白名单；这里再做数据归属、状态机与幂等三重保护，
 * 即使确认链路被重复触发，也不会重复修改业务数据。
 */
@Component
public class CancelOrderTool implements AgentTool {

    static final String TOOL_NAME = "cancel_order";

    private final JdbcTemplate jdbcTemplate;

    public CancelOrderTool(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                TOOL_NAME,
                "取消订单（仅支持 PAID 状态，执行前需用户确认）",
                "{\"type\":\"object\",\"required\":[\"orderId\"],\"properties\":{"
                        + "\"orderId\":{\"type\":\"string\"},"
                        + "\"reason\":{\"type\":\"string\"}}}",
                List.of("orderId"),
                ToolSideEffect.WRITE,
                3000);
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        if (context.userId().isBlank()) {
            return ToolResult.failure("NO_USER", "缺少用户身份，拒绝取消订单",
                    System.currentTimeMillis() - start);
        }
        // 执行层之外的二次保护：即使有人直接调用工具，也不能绕过用户确认
        if (!context.approved()) {
            return ToolResult.failure("CONFIRMATION_REQUIRED", "取消订单需要用户确认后执行",
                    System.currentTimeMillis() - start);
        }
        if (context.idempotencyKey().isBlank()) {
            return ToolResult.failure("MISSING_IDEMPOTENCY_KEY", "写操作缺少幂等键，拒绝执行",
                    System.currentTimeMillis() - start);
        }
        String orderId = args.path("orderId").asText("").trim();
        if (orderId.isBlank()) {
            return ToolResult.failure("INVALID_ARGS", "缺少必填参数: orderId",
                    System.currentTimeMillis() - start);
        }
        String reason = args.path("reason").asText("").trim();
        if (reason.isBlank()) {
            reason = "用户主动取消";
        }

        // 步骤1：状态机 + 归属 + 幂等键一起放进 WHERE，单条 UPDATE 完成条件更新
        int updated = jdbcTemplate.update(
                "UPDATE ai_demo_order SET status = 'CANCELLED', cancelled_at = now(), "
                        + "cancel_reason = ?, cancel_request_id = ? "
                        + "WHERE order_id = ? AND owner_user_id = ? AND status = 'PAID'",
                reason, context.idempotencyKey(), orderId, context.userId());
        if (updated > 0) {
            return success(orderId, reason, false, start);
        }

        // 步骤2：更新 0 行时回查，区分不存在、已取消与状态不允许
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "SELECT status, cancel_request_id FROM ai_demo_order "
                        + "WHERE order_id = ? AND owner_user_id = ?",
                orderId, context.userId());
        if (rows.isEmpty()) {
            return ToolResult.failure("NOT_FOUND", "未找到订单 " + orderId,
                    System.currentTimeMillis() - start);
        }
        Map<String, Object> row = rows.get(0);
        String status = String.valueOf(row.get("status"));
        String requestId = row.get("cancel_request_id") == null
                ? "" : String.valueOf(row.get("cancel_request_id"));
        if ("CANCELLED".equals(status) && context.idempotencyKey().equals(requestId)) {
            // 同一次确认的重放：业务已成功，直接返回成功结果
            return success(orderId, reason, true, start);
        }
        if ("CANCELLED".equals(status)) {
            return ToolResult.failure("INVALID_STATE", "订单 " + orderId + " 已取消",
                    System.currentTimeMillis() - start);
        }
        return ToolResult.failure("INVALID_STATE",
                "订单 " + orderId + " 当前状态为 " + status + "，不支持取消",
                System.currentTimeMillis() - start);
    }

    private ToolResult success(String orderId, String reason, boolean idempotentReplay, long start) {
        Map<String, Object> data = Map.of(
                "orderId", orderId,
                "status", "CANCELLED",
                "reason", reason,
                "idempotentReplay", idempotentReplay);
        String summary = idempotentReplay
                ? "订单 " + orderId + " 已取消（幂等重放，未重复修改）"
                : "订单 " + orderId + " 已取消，原因：" + reason;
        return ToolResult.success(data, summary,
                List.of(new ToolEvidence("TOOL", orderId, "订单取消",
                        "orderId=" + orderId + ", status=CANCELLED, reason=" + reason, 1.0)),
                System.currentTimeMillis() - start);
    }
}
