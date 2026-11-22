package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.service.PromptTemplateService;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ToolAccessPolicy;
import com.example.langchain4j.tool.workflow.TaskPlanValidator;
import com.example.langchain4j.tool.workflow.TaskWorkflow;
import com.example.langchain4j.tool.workflow.TaskWorkflowRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

/** 任务规划节点单测：固定工作流、整组权限预检、参数补齐与契约校验。 */
@ExtendWith(MockitoExtension.class)
class TaskPlanNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String READ_TOOL = "read_tool";
    private static final String WRITE_TOOL = "write_tool";
    private static final String READ_SCHEMA = "{\"type\":\"object\",\"required\":[\"entityId\"],"
            + "\"properties\":{\"entityId\":{\"type\":\"string\"}}}";

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private TaskWorkflowRegistry workflowRegistry;
    @Mock
    private ToolAccessPolicy accessPolicy;

    private AgentProperties properties;
    private TaskWorkflow readWorkflow;
    private ToolDefinition readDefinition;
    private TaskPlanNode node;

    @BeforeEach
    void setUp() {
        properties = new AgentProperties();
        properties.getTool().setMaxStepsPerTask(3);
        properties.getTool().setWriteEnabled(true);
        readWorkflow = workflow("wf_read", READ_TOOL);
        readDefinition = definition(READ_TOOL, ToolSideEffect.READ_ONLY);
        TaskPlanValidator planValidator = new TaskPlanValidator(workflowRegistry, List.of());
        node = new TaskPlanNode(llmGateway, promptTemplateService, workflowRegistry,
                accessPolicy, planValidator, properties);

        lenient().when(promptTemplateService.resolve(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
        lenient().when(promptTemplateService.resolveFormatted(anyString(), anyString(), any()))
                .thenAnswer(invocation -> String.format(
                        invocation.getArgument(1), new Object[]{invocation.getArgument(2)}));
        lenient().when(workflowRegistry.all()).thenReturn(List.of(readWorkflow));
        lenient().when(workflowRegistry.find("wf_read")).thenReturn(Optional.of(readWorkflow));
        lenient().when(workflowRegistry.definitions(readWorkflow)).thenReturn(List.of(readDefinition));
        lenient().when(workflowRegistry.validatePlan(any(TaskWorkflow.class), any()))
                .thenReturn(TaskWorkflowRegistry.Validation.ok());
        lenient().when(accessPolicy.checkAll(eq(readWorkflow), any()))
                .thenReturn(allowed());
    }

    private WorkflowState stateOf(String query) {
        return new WorkflowState(Map.of(
                WorkflowState.QUERY, query,
                WorkflowState.MODEL, "test-model",
                WorkflowState.USER_ID, "demo"));
    }

    private JsonNode json(String content) throws Exception {
        return MAPPER.readTree(content);
    }

    private JsonNode selected(String workflowId) throws Exception {
        return json("{\"workflowId\":\"" + workflowId + "\"}");
    }

    private TaskWorkflow workflow(String id, String... tools) {
        return new TaskWorkflow() {
            @Override
            public String id() {
                return id;
            }

            @Override
            public String description() {
                return "测试任务";
            }

            @Override
            public List<Stage> stages() {
                return List.of(new Stage("main", "主阶段", List.of(tools)));
            }
        };
    }

    private ToolDefinition definition(String name, ToolSideEffect sideEffect) {
        return new ToolDefinition(name, "测试工具", READ_SCHEMA, sideEffect, 3000);
    }

    private ToolAccessPolicy.Decision allowed() {
        return new ToolAccessPolicy.Decision(true, ToolAccessPolicy.Status.ALLOWED, "", "");
    }

    @Test
    @DisplayName("固定工作流内的合法计划 -> 写入工具步骤")
    void planNormal() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(selected("wf_read"), json("""
                {"entities":{"entityId":"B1001"},"missing":[],
                 "steps":[{"tool":"read_tool",
                           "args":{"entityId":"B1001"},
                           "purpose":"查询业务对象状态"}]}
                """));

        Map<String, Object> updates = node.apply(stateOf("帮我查业务对象 B1001 的状态"));

        assertThat(updates.get(WorkflowState.ERROR)).isEqualTo("");
        assertThat(updates.get(WorkflowState.TASK_WORKFLOW_ID)).isEqualTo("wf_read");
        assertThat(updates.get(WorkflowState.TASK_CLARIFY)).isEqualTo(false);
        assertThat((List<?>) updates.get(WorkflowState.TASK_PLAN)).hasSize(1);
        assertThat(updates.get(WorkflowState.TASK_CONFIRMATION_REQUIRED)).isEqualTo(false);
    }

    @Test
    @DisplayName("写工具 -> 标记待确认并保留计划，不直接执行")
    void planWriteToolRequiresConfirmation() throws Exception {
        TaskWorkflow writeWorkflow = workflow("wf_write", WRITE_TOOL);
        ToolDefinition writeDefinition = definition(WRITE_TOOL, ToolSideEffect.WRITE);
        when(workflowRegistry.all()).thenReturn(List.of(writeWorkflow));
        when(workflowRegistry.find("wf_write")).thenReturn(Optional.of(writeWorkflow));
        when(workflowRegistry.definitions(writeWorkflow)).thenReturn(List.of(writeDefinition));
        when(accessPolicy.checkAll(eq(writeWorkflow), any())).thenReturn(allowed());
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(selected("wf_write"), json("""
                {"entities":{"entityId":"B1001"},"missing":[],
                 "steps":[{"tool":"write_tool",
                           "args":{"entityId":"B1001"},
                           "purpose":"修改业务数据"}]}
                """));

        Map<String, Object> updates = node.apply(stateOf("修改业务对象 B1001"));

        assertThat(updates.get(WorkflowState.TASK_CONFIRMATION_REQUIRED)).isEqualTo(true);
        assertThat((Map<String, Object>) updates.get(WorkflowState.TASK_PENDING_ACTION))
                .containsEntry("tool", WRITE_TOOL);
    }

    @Test
    @DisplayName("计划工具与固定契约不一致 -> fail-closed")
    void rejectToolSequenceMismatch() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(selected("wf_read"), json("""
                {"entities":{"entityId":"B1001"},"missing":[],
                 "steps":[{"tool":"other_tool","args":{},"purpose":"越权"}]}
                """));

        Map<String, Object> updates = node.apply(stateOf("帮我查询"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("固定工具契约");
    }

    @Test
    @DisplayName("计划少工具 -> fail-closed，不执行半份计划")
    void rejectMissingTool() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(selected("wf_read"), json("""
                {"entities":{"entityId":"B1001"},"missing":[],"steps":[]}
                """));

        Map<String, Object> updates = node.apply(stateOf("帮我查询"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("应包含 1 步");
    }

    @Test
    @DisplayName("缺少必填业务参数 -> 生成追问，不执行工具")
    void clarifyWhenMissingSlot() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(selected("wf_read"), json("""
                {"entities":{"entityId":""},"missing":["entityId"],"steps":[]}
                """));

        Map<String, Object> updates = node.apply(stateOf("帮我查一下业务对象状态"));

        assertThat(updates.get(WorkflowState.TASK_CLARIFY)).isEqualTo(true);
        assertThat(String.valueOf(updates.get(WorkflowState.RESULT))).contains("entityId");
        assertThat((List<?>) updates.get(WorkflowState.TASK_PLAN)).isEmpty();
    }

    @Test
    @DisplayName("流程头节点权限预检失败 -> 整个任务拒绝")
    void rejectWhenWorkflowPermissionDenied() throws Exception {
        when(accessPolicy.checkAll(eq(readWorkflow), any())).thenReturn(
                new ToolAccessPolicy.Decision(false,
                        ToolAccessPolicy.Status.PERMISSION_REQUIRED, READ_TOOL,
                        "当前角色无权执行该任务"));
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(selected("wf_read"));

        Map<String, Object> updates = node.apply(stateOf("帮我查询"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("无权");
    }

    @Test
    @DisplayName("模型选择未知工作流 -> fail-closed")
    void rejectUnknownWorkflow() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(selected("wf_unknown"));

        Map<String, Object> updates = node.apply(stateOf("帮我查询"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("不存在或不可用");
    }

    @Test
    @DisplayName("计划参数不符合 Schema -> fail-closed")
    void rejectInvalidPlanArgs() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(selected("wf_read"), json("""
                {"entities":{},"missing":[],
                 "steps":[{"tool":"read_tool","args":{},"purpose":"查询"}]}
                """));

        Map<String, Object> updates = node.apply(stateOf("帮我查询"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("参数不合法");
    }

    @Test
    @DisplayName("工作流超过最大步数 -> 规划前拒绝")
    void rejectTooManyWorkflowSteps() throws Exception {
        TaskWorkflow large = workflow("wf_large", "a", "b", "c", "d");
        when(workflowRegistry.all()).thenReturn(List.of(large));
        when(workflowRegistry.find("wf_large")).thenReturn(Optional.of(large));
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(selected("wf_large"));

        Map<String, Object> updates = node.apply(stateOf("执行复杂任务"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("超过当前上限");
    }

    @Test
    @DisplayName("工作流选择失败 -> 标记错误")
    void failWhenSelectionReturnsNull() {
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(null);

        Map<String, Object> updates = node.apply(stateOf("帮我查询"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("任务类型识别失败");
    }
}
