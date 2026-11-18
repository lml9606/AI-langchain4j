package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.runtime.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * 任务执行节点单测：按计划调用工具、失败不中断、证据与轨迹分别沉淀。
 */
@ExtendWith(MockitoExtension.class)
class TaskExecuteNodeTest {

    @Mock
    private ToolExecutor toolExecutor;

    private TaskExecuteNode node;

    @BeforeEach
    void setUp() {
        node = new TaskExecuteNode(toolExecutor);
    }

    private WorkflowState stateWithPlan() {
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, "帮我查业务对象 B1001",
                WorkflowState.MODEL, "test-model",
                WorkflowState.USER_ID, "demo",
                WorkflowState.CONVERSATION_ID, "c1",
                WorkflowState.TASK_PLAN, List.of(
                        Map.of("tool", "read_tool",
                                "args", Map.of("queryType", "status_query", "entityId", "B1001"),
                                "purpose", "查业务对象状态"),
                        Map.of("tool", "http_tool",
                                "args", Map.of("entityId", "B1001"),
                                "purpose", "查业务对象详情"))));
    }

    @Test
    @DisplayName("成功与失败混合 → 轨迹保留两条，证据只沉淀成功步骤")
    void collectTraceAndEvidence() {
        when(toolExecutor.invoke(any(), eq("read_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.success(List.of(Map.of("status", "PAID")),
                        "业务对象 B1001 状态 PAID",
                        List.of(new ToolEvidence("TOOL", "B1001", "业务对象状态",
                                "{\"status\":\"PAID\"}", 0.0)), 12));
        when(toolExecutor.invoke(any(), eq("http_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.failure("HTTP_404", "业务对象不存在", 8));

        Map<String, Object> updates = node.apply(stateWithPlan());

        assertThat(updates.get(WorkflowState.ERROR)).isEqualTo("");
        assertThat((List<?>) updates.get(WorkflowState.TASK_TRACE)).hasSize(2);
        assertThat((List<?>) updates.get(WorkflowState.TASK_EVIDENCE)).hasSize(1);
        assertThat(String.valueOf(updates.get(WorkflowState.TASK_TRACE)))
                .contains("read_tool")
                .contains("HTTP_404");
    }

    @Test
    @DisplayName("参数 Map 正确转成 JsonNode 传给执行器")
    void passArgsAsJson() {
        when(toolExecutor.invoke(any(), eq("read_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.success(List.of(), "空", List.of(), 1));
        when(toolExecutor.invoke(any(), eq("http_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.success(List.of(), "空", List.of(), 1));
        ArgumentCaptor<JsonNode> captor = ArgumentCaptor.forClass(JsonNode.class);

        node.apply(stateWithPlan());

        org.mockito.Mockito.verify(toolExecutor,
                        org.mockito.Mockito.atLeastOnce())
                .invoke(any(), anyString(), captor.capture());
        assertThat(captor.getAllValues().get(0).path("entityId").asText()).isEqualTo("B1001");
    }

    @Test
    @DisplayName("确认执行 → ToolContext 携带 approved=true 与幂等键")
    void passApprovedContext() {
        when(toolExecutor.invoke(any(), eq("read_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.success(List.of(), "空", List.of(), 1));
        WorkflowState state = new WorkflowState(Map.of(
                WorkflowState.QUERY, "确认执行修改业务数据",
                WorkflowState.MODEL, "test-model",
                WorkflowState.USER_ID, "demo",
                WorkflowState.CONVERSATION_ID, "c1",
                WorkflowState.TASK_APPROVED, true,
                WorkflowState.TASK_IDEMPOTENCY_KEY, "cfm_1",
                WorkflowState.TASK_PLAN, List.of(
                        Map.of("tool", "read_tool",
                                "args", Map.of("entityId", "B1001"),
                                "purpose", "修改业务数据"))));
        ArgumentCaptor<ToolContext> captor = ArgumentCaptor.forClass(ToolContext.class);

        node.apply(state);

        org.mockito.Mockito.verify(toolExecutor,
                        org.mockito.Mockito.atLeastOnce())
                .invoke(captor.capture(), anyString(), any(JsonNode.class));
        assertThat(captor.getValue().approved()).isTrue();
        assertThat(captor.getValue().idempotencyKey()).isEqualTo("cfm_1");
    }
}
