package com.example.langchain4j.tool.provider;

import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * 取消订单写工具单测：归属、状态机与幂等分支。
 */
@ExtendWith(MockitoExtension.class)
class CancelOrderToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final ToolContext CONTEXT = new ToolContext("demo", "c1", true, "cfm_1");

    @Mock
    private JdbcTemplate jdbcTemplate;

    private CancelOrderTool tool;

    @BeforeEach
    void setUp() {
        tool = new CancelOrderTool(jdbcTemplate);
    }

    @Test
    @DisplayName("工具定义为 WRITE，且必须确认")
    void definitionIsWrite() {
        ToolDefinition definition = tool.definition();
        assertThat(definition.name()).isEqualTo("cancel_order");
        assertThat(definition.sideEffect()).isEqualTo(ToolSideEffect.WRITE);
    }

    @Test
    @DisplayName("缺少用户身份 → NO_USER")
    void rejectMissingUser() throws Exception {
        ToolResult result = tool.execute(new ToolContext("", "c1", true, "cfm_1"),
                MAPPER.readTree("{\"orderId\":\"A1001\"}"));
        assertThat(result.errorCode()).isEqualTo("NO_USER");
    }

    @Test
    @DisplayName("未确认直接调用 → CONFIRMATION_REQUIRED")
    void rejectUnapprovedDirectCall() throws Exception {
        ToolResult result = tool.execute(new ToolContext("demo", "c1"),
                MAPPER.readTree("{\"orderId\":\"A1001\"}"));
        assertThat(result.errorCode()).isEqualTo("CONFIRMATION_REQUIRED");
    }

    @Test
    @DisplayName("缺少幂等键 → MISSING_IDEMPOTENCY_KEY")
    void rejectMissingIdempotencyKey() throws Exception {
        ToolResult result = tool.execute(new ToolContext("demo", "c1", true, ""),
                MAPPER.readTree("{\"orderId\":\"A1001\"}"));
        assertThat(result.errorCode()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    }

    @Test
    @DisplayName("更新成功 → 返回 CANCELLED 与证据")
    void cancelSuccess() throws Exception {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(1);

        ToolResult result = tool.execute(CONTEXT,
                MAPPER.readTree("{\"orderId\":\"A1001\",\"reason\":\"不想要了\"}"));

        assertThat(result.success()).isTrue();
        assertThat(String.valueOf(result.data())).contains("CANCELLED");
        assertThat(result.evidence()).hasSize(1);
    }

    @Test
    @DisplayName("同一幂等键重放 → 返回成功且标记幂等重放")
    void idempotentReplay() throws Exception {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("status", "CANCELLED", "cancel_request_id", "cfm_1")));

        ToolResult result = tool.execute(CONTEXT,
                MAPPER.readTree("{\"orderId\":\"A1001\"}"));

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("幂等重放");
    }

    @Test
    @DisplayName("订单状态不允许取消 → INVALID_STATE")
    void rejectInvalidState() throws Exception {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class)))
                .thenReturn(List.of(Map.of("status", "SHIPPED")));

        ToolResult result = tool.execute(CONTEXT,
                MAPPER.readTree("{\"orderId\":\"A1003\"}"));

        assertThat(result.errorCode()).isEqualTo("INVALID_STATE");
        assertThat(result.errorMessage()).contains("SHIPPED");
    }

    @Test
    @DisplayName("订单不存在 → NOT_FOUND")
    void orderNotFound() throws Exception {
        when(jdbcTemplate.update(anyString(), any(Object[].class))).thenReturn(0);
        when(jdbcTemplate.queryForList(anyString(), any(Object[].class))).thenReturn(List.of());

        ToolResult result = tool.execute(CONTEXT,
                MAPPER.readTree("{\"orderId\":\"A9999\"}"));

        assertThat(result.errorCode()).isEqualTo("NOT_FOUND");
    }
}
