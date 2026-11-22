package com.example.langchain4j.tool.workflow;

import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

/** 任务计划校验器单测：内置安全规则优先，扩展规则只能追加拒绝。 */
@ExtendWith(MockitoExtension.class)
class TaskPlanValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String ENTITY_SCHEMA = """
            {"type":"object","required":["entityId"],
             "properties":{"entityId":{"type":"string"}}}
            """;

    @Mock
    private TaskWorkflowRegistry workflowRegistry;

    private TaskWorkflow workflow;
    private Map<String, ToolDefinition> definitions;

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
                return List.of(new Stage("main", "主阶段",
                        List.of("read_tool", "write_tool")));
            }
        };
        definitions = Map.of(
                "read_tool", new ToolDefinition("read_tool", "读取", ENTITY_SCHEMA,
                        ToolSideEffect.READ_ONLY, 3000),
                "write_tool", new ToolDefinition("write_tool", "写入", ENTITY_SCHEMA,
                        ToolSideEffect.WRITE, 3000));
        lenient().when(workflowRegistry.validatePlan(eq(workflow), any()))
                .thenReturn(TaskWorkflowRegistry.Validation.ok());
    }

    private JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    private Map<String, Object> step(String tool) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("workflowId", "wf_test");
        step.put("tool", tool);
        step.put("args", Map.of("entityId", "B1001"));
        step.put("purpose", "测试");
        return step;
    }

    @Test
    @DisplayName("steps 数量与固定契约不一致 -> parse 阶段拒绝")
    void rejectStepCount() throws Exception {
        TaskPlanValidator validator = new TaskPlanValidator(workflowRegistry, List.of());

        TaskPlanValidator.ParseResult result = validator.parseSteps(
                json("[]"), workflow, definitions, Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("应包含 2 步");
    }

    @Test
    @DisplayName("参数 Schema 不合法 -> 统一校验入口拒绝")
    void rejectInvalidArgs() {
        TaskPlanValidator validator = new TaskPlanValidator(workflowRegistry, List.of());
        Map<String, Object> step = step("read_tool");
        step.put("args", Map.of());

        TaskPlanValidator.PlanValidationResult result = validator.validate(
                TaskPlanPhase.PLAN, workflow, List.of(step), definitions, Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("参数不合法");
    }

    @Test
    @DisplayName("多个写操作 -> 规划阶段拒绝")
    void rejectMultipleWrites() {
        TaskPlanValidator validator = new TaskPlanValidator(workflowRegistry, List.of());

        TaskPlanValidator.PlanValidationResult result = validator.validate(
                TaskPlanPhase.PLAN, workflow,
                List.of(step("write_tool"), step("write_tool")), definitions, Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("一次只能确认一个写操作");
    }

    @Test
    @DisplayName("确认阶段没有写操作 -> 拒绝")
    void rejectConfirmWithoutWrite() {
        TaskPlanValidator validator = new TaskPlanValidator(workflowRegistry, List.of());

        TaskPlanValidator.PlanValidationResult result = validator.validate(
                TaskPlanPhase.CONFIRM, workflow, List.of(step("read_tool")),
                definitions, Map.of("tool", "read_tool", "args", Map.of("entityId", "B1001")));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("没有待确认的写操作");
    }

    @Test
    @DisplayName("pending 参数与计划写步骤不一致 -> 拒绝")
    void rejectPendingArgsMismatch() {
        TaskPlanValidator validator = new TaskPlanValidator(workflowRegistry, List.of());

        TaskPlanValidator.PlanValidationResult result = validator.validate(
                TaskPlanPhase.CONFIRM, workflow, List.of(step("write_tool")), definitions,
                Map.of("tool", "write_tool", "args", Map.of("entityId", "B2002")));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("待确认参数与执行计划不一致");
    }

    @Test
    @DisplayName("内置规则失败时扩展规则不执行，避免业务规则绕过安全边界")
    void extensionCannotRunAfterBuiltInFailure() {
        TaskPlanRule rule = mock(TaskPlanRule.class);
        TaskPlanValidator validator = new TaskPlanValidator(workflowRegistry, List.of(rule));

        TaskPlanValidator.PlanValidationResult result = validator.validate(
                TaskPlanPhase.PLAN, workflow,
                List.of(step("write_tool"), step("write_tool")), definitions, Map.of());

        assertThat(result.valid()).isFalse();
        verifyNoInteractions(rule);
    }

    @Test
    @DisplayName("扩展规则可追加业务拒绝原因")
    void extensionCanReject() {
        TaskPlanRule rule = context -> ValidationResult.invalid("QUOTA_EXCEEDED", "超过租户额度");
        TaskPlanValidator validator = new TaskPlanValidator(workflowRegistry, List.of(rule));

        TaskPlanValidator.PlanValidationResult result = validator.validate(
                TaskPlanPhase.PLAN, workflow, List.of(step("read_tool")), definitions, Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("超过租户额度");
    }

    @Test
    @DisplayName("扩展规则通过后返回计划中的写步骤")
    void extensionPassKeepsWriteStep() {
        TaskPlanRule rule = context -> ValidationResult.ok();
        TaskPlanValidator validator = new TaskPlanValidator(workflowRegistry, List.of(rule));

        TaskPlanValidator.PlanValidationResult result = validator.validate(
                TaskPlanPhase.PLAN, workflow, List.of(step("write_tool")), definitions, Map.of());

        assertThat(result.valid()).isTrue();
        assertThat(result.writeStep()).containsEntry("tool", "write_tool");
        assertThat(result.writeDefinition().sideEffect()).isEqualTo(ToolSideEffect.WRITE);
    }

    @Test
    @DisplayName("扩展规则拿到只读快照 -> 修改计划会被 fail-closed，原计划不受影响")
    void extensionCannotMutatePlan() {
        Map<String, Object> original = step("write_tool");
        List<Map<String, Object>> plan = List.of(original);
        TaskPlanRule rule = context -> {
            context.plan().get(0).put("tool", "tampered_tool");
            return ValidationResult.ok();
        };
        TaskPlanValidator validator = new TaskPlanValidator(workflowRegistry, List.of(rule));

        TaskPlanValidator.PlanValidationResult result = validator.validate(
                TaskPlanPhase.PLAN, workflow, plan, definitions, Map.of());

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("扩展校验执行失败");
        assertThat(original).containsEntry("tool", "write_tool");
    }
}
