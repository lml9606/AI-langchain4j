package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.runtime.ToolAccessPolicy;
import com.example.langchain4j.tool.runtime.ToolExecutor;
import com.example.langchain4j.tool.workflow.TaskWorkflow;
import com.example.langchain4j.tool.workflow.TaskWorkflowRegistry;
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
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/** 任务执行节点单测：整组契约复核、逐节点权限、轨迹与证据沉淀。 */
@ExtendWith(MockitoExtension.class)
class TaskExecuteNodeTest {

    @Mock
    private ToolExecutor toolExecutor;
    @Mock
    private TaskWorkflowRegistry workflowRegistry;
    @Mock
    private ToolAccessPolicy accessPolicy;

    private TaskWorkflow workflow;
    private TaskExecuteNode node;

    @BeforeEach
    void setUp() {
        workflow = new TaskWorkflow() {
            @Override
            public String id() {
                return "wf_test";
            }

            @Override
            public String description() {
                return "测试任务";
            }

            @Override
            public List<Stage> stages() {
                return List.of(new Stage("main", "主阶段", List.of("read_tool", "http_tool")));
            }
        };
        node = new TaskExecuteNode(toolExecutor, new AgentProperties(), workflowRegistry,
                accessPolicy);
        lenient().when(workflowRegistry.find("wf_test")).thenReturn(Optional.of(workflow));
        lenient().when(workflowRegistry.validatePlan(eq(workflow), any()))
                .thenReturn(TaskWorkflowRegistry.Validation.ok());
        lenient().when(accessPolicy.checkAll(eq(workflow), any())).thenReturn(allowed(""));
        lenient().when(accessPolicy.check(anyString(), any())).thenReturn(allowed(""));
    }

    private ToolAccessPolicy.Decision allowed(String tool) {
        return new ToolAccessPolicy.Decision(true, ToolAccessPolicy.Status.ALLOWED, tool, "");
    }

    private WorkflowState stateWithPlan() {
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, "帮我查业务对象 B1001",
                WorkflowState.MODEL, "test-model",
                WorkflowState.USER_ID, "demo",
                WorkflowState.CONVERSATION_ID, "c1",
                WorkflowState.TASK_WORKFLOW_ID, "wf_test",
                WorkflowState.TASK_PLAN, List.of(
                        Map.of("workflowId", "wf_test", "tool", "read_tool",
                                "args", Map.of("queryType", "status_query", "entityId", "B1001"),
                                "purpose", "查业务对象状态"),
                        Map.of("workflowId", "wf_test", "tool", "http_tool",
                                "args", Map.of("entityId", "B1001"),
                                "purpose", "查业务对象详情"))));
    }

    @Test
    @DisplayName("成功与失败混合 -> 轨迹保留两条，证据只沉淀成功步骤")
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
    @DisplayName("确认执行 -> ToolContext 携带 approved=true 与幂等键")
    void passApprovedContext() {
        when(toolExecutor.invoke(any(), eq("read_tool"), any(JsonNode.class)))
                .thenReturn(ToolResult.success(List.of(), "空", List.of(), 1));
        WorkflowState state = new WorkflowState(Map.of(
                WorkflowState.QUERY, "确认执行修改业务数据",
                WorkflowState.MODEL, "test-model",
                WorkflowState.USER_ID, "demo",
                WorkflowState.CONVERSATION_ID, "c1",
                WorkflowState.TASK_WORKFLOW_ID, "wf_test",
                WorkflowState.TASK_APPROVED, true,
                WorkflowState.TASK_IDEMPOTENCY_KEY, "cfm_1",
                WorkflowState.TASK_PLAN, List.of(
                        Map.of("workflowId", "wf_test", "tool", "read_tool",
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

    @Test
    @DisplayName("缺少工作流契约 -> 不执行任何工具")
    void rejectMissingWorkflowContract() {
        WorkflowState state = new WorkflowState(Map.of(
                WorkflowState.QUERY, "执行",
                WorkflowState.USER_ID, "demo",
                WorkflowState.TASK_PLAN, List.of(Map.of("tool", "read_tool"))));

        Map<String, Object> updates = node.apply(state);

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("缺少固定工作流契约");
        org.mockito.Mockito.verifyNoInteractions(toolExecutor);
    }

    @Test
    @DisplayName("执行期契约不一致 -> 整组拒绝，不执行半份计划")
    void rejectPlanMismatchBeforeExecution() {
        when(workflowRegistry.validatePlan(eq(workflow), any()))
                .thenReturn(TaskWorkflowRegistry.Validation.invalid("契约不一致"));

        Map<String, Object> updates = node.apply(stateWithPlan());

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("契约不一致");
        org.mockito.Mockito.verifyNoInteractions(toolExecutor);
    }

    @Test
    @DisplayName("子节点权限被收回 -> 停止执行并返回明确错误")
    void rejectWhenStepPermissionRevoked() {
        when(accessPolicy.check(eq("read_tool"), any())).thenReturn(
                new ToolAccessPolicy.Decision(false,
                        ToolAccessPolicy.Status.PERMISSION_REQUIRED, "read_tool",
                        "当前角色无权执行该任务"));

        Map<String, Object> updates = node.apply(stateWithPlan());

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("无权");
        org.mockito.Mockito.verifyNoInteractions(toolExecutor);
    }
}
