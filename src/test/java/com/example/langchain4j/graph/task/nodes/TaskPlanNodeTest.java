package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.service.PromptTemplateService;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ToolRegistry;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 任务规划节点单测：正常计划、缺参追问、未知工具过滤与规划失败降级。
 */
@ExtendWith(MockitoExtension.class)
class TaskPlanNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String TOOL = "read_tool";

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private ToolRegistry toolRegistry;
    @Mock
    private AgentTool agentTool;

    private TaskPlanNode node;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getTool().setMaxStepsPerTask(3);
        node = new TaskPlanNode(llmGateway, promptTemplateService, toolRegistry, properties);
        lenient().when(promptTemplateService.resolveFormatted(anyString(), anyString(), any()))
                .thenAnswer(invocation -> String.format(
                        invocation.getArgument(1), new Object[]{invocation.getArgument(2)}));
        lenient().when(toolRegistry.definitions()).thenReturn(List.of(
                new ToolDefinition(TOOL, "查询业务对象或业务记录", "{}", List.of("entityId"),
                        ToolSideEffect.READ_ONLY, 3000)));
        // 默认按只读工具建模；写工具用例再覆盖 definition()
        lenient().when(agentTool.definition()).thenReturn(new ToolDefinition(
                TOOL, "查询业务对象或业务记录", "{}", List.of("entityId"),
                ToolSideEffect.READ_ONLY, 3000));
        lenient().when(toolRegistry.find(TOOL)).thenReturn(Optional.of(agentTool));
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

    @Test
    @DisplayName("正常计划 → 写入工具步骤，clarify=false")
    void planNormal() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class)))
                .thenReturn(json("""
                        {"entities":{"entityId":"B1001"},"missing":[],
                         "steps":[{"tool":"read_tool",
                                   "args":{"queryType":"status_query","entityId":"B1001"},
                                   "purpose":"查询业务对象状态"}]}
                        """));

        Map<String, Object> updates = node.apply(stateOf("帮我查业务对象 B1001 的状态"));

        assertThat(updates.get(WorkflowState.ERROR)).isEqualTo("");
        assertThat(updates.get(WorkflowState.TASK_CLARIFY)).isEqualTo(false);
        assertThat((List<?>) updates.get(WorkflowState.TASK_PLAN)).hasSize(1);
        assertThat(updates.get(WorkflowState.TASK_CONFIRMATION_REQUIRED)).isEqualTo(false);
    }

    @Test
    @DisplayName("计划包含写工具 → 标记待确认并保留计划，不直接执行")
    void planWriteToolRequiresConfirmation() throws Exception {
        when(agentTool.definition()).thenReturn(new ToolDefinition(
                TOOL, "修改业务数据", "{}", List.of("entityId"), ToolSideEffect.WRITE, 3000));
        when(llmGateway.generateJson(any(LlmRequest.class)))
                .thenReturn(json("""
                        {"entities":{"entityId":"B1001"},"missing":[],
                         "steps":[{"tool":"read_tool",
                                   "args":{"entityId":"B1001"},
                                   "purpose":"修改业务数据"}]}
                        """));

        Map<String, Object> updates = node.apply(stateOf("修改业务数据 B1001"));

        assertThat(updates.get(WorkflowState.ERROR)).isEqualTo("");
        assertThat(updates.get(WorkflowState.TASK_CONFIRMATION_REQUIRED)).isEqualTo(true);
        @SuppressWarnings("unchecked")
        Map<String, Object> pending = (Map<String, Object>) updates.get(WorkflowState.TASK_PENDING_ACTION);
        assertThat(pending)
                .containsEntry("tool", TOOL)
                .containsEntry("risk", "WRITE");
        assertThat((List<?>) updates.get(WorkflowState.TASK_PLAN)).hasSize(1);
    }

    @Test
    @DisplayName("一次计划包含多个写工具 → 拒绝，要求拆分")
    void rejectMultipleWriteSteps() throws Exception {
        when(agentTool.definition()).thenReturn(new ToolDefinition(
                TOOL, "修改业务数据", "{}", List.of("entityId"), ToolSideEffect.WRITE, 3000));
        when(llmGateway.generateJson(any(LlmRequest.class)))
                .thenReturn(json("""
                        {"entities":{"entityId":"B1001"},"missing":[],
                         "steps":[
                           {"tool":"read_tool","args":{"entityId":"B1001"},"purpose":"修改1"},
                           {"tool":"read_tool","args":{"entityId":"B1002"},"purpose":"修改2"}
                         ]}
                        """));

        Map<String, Object> updates = node.apply(stateOf("修改两个业务对象"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("一次只能确认一个写操作");
    }

    @Test
    @DisplayName("缺少必填业务参数 → 生成追问，不执行工具")
    void clarifyWhenMissingSlot() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class)))
                .thenReturn(json("""
                        {"entities":{"entityId":""},"missing":["entityId"],"steps":[]}
                        """));

        Map<String, Object> updates = node.apply(stateOf("帮我查一下业务对象状态"));

        assertThat(updates.get(WorkflowState.TASK_CLARIFY)).isEqualTo(true);
        assertThat(String.valueOf(updates.get(WorkflowState.RESULT))).contains("entityId");
        assertThat((List<?>) updates.get(WorkflowState.TASK_PLAN)).isEmpty();
    }

    @Test
    @DisplayName("未知工具被过滤；无可用步骤 → 标记规划失败")
    void rejectUnknownTool() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class)))
                .thenReturn(json("""
                        {"entities":{"entityId":"B1001"},"missing":[],
                         "steps":[{"tool":"delete_everything","args":{},"purpose":"危险操作"}]}
                        """));

        Map<String, Object> updates = node.apply(stateOf("帮我查业务对象 B1001"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("计划为空");
    }

    @Test
    @DisplayName("LLM 返回 null → 标记错误，交由路由兜底")
    void failWhenLlmReturnsNull() {
        when(llmGateway.generateJson(any(LlmRequest.class)))
                .thenReturn(null);

        Map<String, Object> updates = node.apply(stateOf("帮我查业务对象 B1001"));

        assertThat(String.valueOf(updates.get(WorkflowState.ERROR))).contains("任务计划生成失败");
    }
}
