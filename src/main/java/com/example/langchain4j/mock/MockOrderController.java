package com.example.langchain4j.mock;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 本地演示用订单查询接口：供 HTTP 工具验证“外部 API 包装成工具”的完整链路。
 * <p>
 * 不属于正式业务接口：路径不在 /api/** 下，JwtAuthFilter 不拦截；
 * 生产接入真实业务系统后可直接删除本类并修改 agent.tool.http.base-url。
 */
@RestController
@RequestMapping("/mock")
public class MockOrderController {

    private static final Map<String, Map<String, Object>> ORDERS = Map.of(
            "A1001", order("A1001", "PAID", 199.00, "2026-11-10T10:15:00+08:00"),
            "A1002", order("A1002", "REFUND_FAILED", 89.90, "2026-11-08T16:40:00+08:00"),
            "A1003", order("A1003", "SHIPPED", 328.00, "2026-11-12T09:05:00+08:00"));

    private final JdbcTemplate jdbcTemplate;

    public MockOrderController(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询订单详情（演示数据）。
     *
     * @param orderId 订单号
     * @param userId  调用方用户 ID（HTTP 工具透传 X-User-Id，用于展示链路透传）
     * @return 200 = 订单 JSON；404 = 订单不存在
     */
    @GetMapping("/orders/{orderId}")
    public ResponseEntity<Map<String, Object>> getOrder(
            @PathVariable String orderId,
            @RequestHeader(value = "X-User-Id", required = false) String userId) {
        String owner = userId == null || userId.isBlank() ? "demo" : userId;
        // 优先读业务表，保证取消订单后 HTTP 查询与 SQL 查询看到同一状态；DB 不可用时回退静态演示数据
        Map<String, Object> body = loadFromDatabase(orderId, owner);
        if (body == null) {
            Map<String, Object> demo = ORDERS.get(orderId);
            if (demo == null) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(Map.of("error", "order not found", "orderId", orderId));
            }
            body = new LinkedHashMap<>(demo);
        }
        body.put("ownerUserId", owner);
        return ResponseEntity.ok(body);
    }

    private Map<String, Object> loadFromDatabase(String orderId, String owner) {
        try {
            List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                    "SELECT order_id, status, amount, paid_at FROM ai_demo_order "
                            + "WHERE order_id = ? AND owner_user_id = ?",
                    orderId, owner);
            if (rows.isEmpty()) {
                return null;
            }
            Map<String, Object> row = rows.get(0);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("orderId", row.get("order_id"));
            body.put("status", row.get("status"));
            body.put("amount", row.get("amount"));
            body.put("paidAt", row.get("paid_at"));
            return body;
        } catch (Exception e) {
            return null;
        }
    }

    private static Map<String, Object> order(String orderId, String status,
                                             double amount, String paidAt) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("orderId", orderId);
        item.put("status", status);
        item.put("amount", amount);
        item.put("paidAt", paidAt);
        return Map.copyOf(item);
    }
}
