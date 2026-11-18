package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import com.example.langchain4j.tool.runtime.ToolRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 确认节点单测：生成一次性令牌、透出确认卡片、创建失败转兜底。
 */
@ExtendWith(MockitoExtension.class)
class TaskConfirmNodeTest {

    @Mock
    private ConfirmationService confirmationService;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private AgentTool agentTool;

    private TaskConfirmNode node;

    @BeforeEach
    void setUp() {
        node = new TaskConfirmNode(confirmationService, toolRegistry);
        lenient().when(toolRegistry.find("write_tool")).thenReturn(Optional.of(agentTool));
        lenient().when(agentTool.definition()).thenReturn(new ToolDefinition(
                "write_tool", "修改业务数据", "{}", List.of("entityId"),
                ToolSideEffect.WRITE, 3000));
    }

    private WorkflowState state() {
        Map<String, Object> pending = new LinkedHashMap<>();
        pending.put("tool", "write_tool");
        pending.put("args", Map.of("entityId", "B1001", "reason", "不想要了"));
        pending.put("purpose", "修改业务数据");
        pending.put("risk", "WRITE");
        List<Map<String, Object>> plan = List.of(
                Map.of("tool", "write_tool",
                        "args", Map.of("entityId", "B1001", "reason", "不想要了"),
                        "purpose", "修改业务数据"));
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, "修改业务数据 B1001",
                WorkflowState.MODEL, "test-model",
                WorkflowState.USER_ID, "demo",
                WorkflowState.CONVERSATION_ID, "c1",
                WorkflowState.TASK_PLAN, plan,
                WorkflowState.TASK_PENDING_ACTION, pending));
    }

    @Test
    @DisplayName("写操作 → 生成 confirmationId 与确认话术")
    void createConfirmation() {
        List<Map<String, Object>> plan = List.of(
                Map.of("tool", "write_tool",
                        "args", Map.of("entityId", "B1001", "reason", "不想要了"),
                        "purpose", "修改业务数据"));
        ConfirmationService.PendingAction action = new ConfirmationService.PendingAction(
                "cfm_1", "demo", "c1", "test-model", "write_tool", plan,
                "即将执行：修改业务数据。", "WRITE", 1760000000000L);
        when(confirmationService.create(eq("demo"), eq("c1"), eq("test-model"),
                eq("write_tool"), eq(plan), anyString(), eq("WRITE"))).thenReturn(action);

        Map<String, Object> updates = node.apply(state());

        assertThat(updates.get(WorkflowState.ERROR)).isEqualTo("");
        assertThat(updates.get(WorkflowState.TASK_CONFIRMATION_ID)).isEqualTo("cfm_1");
        assertThat(String.valueOf(updates.get(WorkflowState.RESULT))).contains("请确认后执行");
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) updates.get(WorkflowState.TASK_PENDING_ACTION);
        assertThat(pending)
                .containsEntry("confirmationId", "cfm_1")
                .containsEntry("tool", "write_tool");
    }

    @Test
    @DisplayName("确认令牌创建失败 → 标记 error，由路由转兜底")
    void failWhenConfirmationServiceDown() {
        when(confirmationService.create(any(), any(), any(), any(), any(), any(), any()))
                .thenThrow(new BizException(ErrorCode.CACHE_UNAVAILABLE, "redis down"));

        Map<String, Object> updates = node.apply(state());

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("确认请求创建失败");
        assertThat((Map<?, ?>) updates.get(WorkflowState.TASK_PENDING_ACTION)).isEmpty();
        assertThat(updates.get(WorkflowState.TASK_CONFIRMATION_REQUIRED)).isEqualTo(false);
    }
}
