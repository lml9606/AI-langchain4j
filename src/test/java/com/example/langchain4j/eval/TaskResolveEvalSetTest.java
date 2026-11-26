package com.example.langchain4j.eval;

import com.example.langchain4j.common.context.ContextAssembler;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.nodes.TaskResolveNode;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.model.LlmRequest;
import com.example.langchain4j.prompt.PromptConfigMapper;
import com.example.langchain4j.prompt.PromptTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 任务解析测评集：用预先编写的模型输出验证任务路由、参数解析、缺参追问和写操作门禁。
 *
 * 该测试不连接数据库、Redis 或真实模型，适合每次构建执行；它验证的是解析层契约，
 * 不替代写操作事务和真实工具执行的集成测试。
 */
class TaskResolveEvalSetTest {

    private static final String GOLDEN_RESOURCE = "eval/golden-task.jsonl";
    private static final List<RecordedResult> RECORDED_RESULTS =
            Collections.synchronizedList(new ArrayList<>());

    /** 校验任务测评集的 ID、查询、模型响应和预期结果完整且唯一，避免坏样本静默失效。 */
    @Test
    void goldenTaskSetIsWellFormed() throws IOException {
        List<TaskEvalCase> cases = loadCases();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(8);
        assertThat(cases).extracting(TaskEvalCase::id).doesNotHaveDuplicates();
        assertThat(cases).extracting(TaskEvalCase::query).doesNotHaveDuplicates();
        assertThat(cases).allSatisfy(evalCase -> {
            assertThat(evalCase.id()).isNotBlank();
            assertThat(evalCase.category()).isNotBlank();
            assertThat(evalCase.query()).isNotBlank();
            assertThat(evalCase.llmResponse()).isNotNull();
            assertThat(evalCase.llmResponse().isObject()).isTrue();
            assertThat(evalCase.expected()).isNotNull();
        });
    }

    /** 逐条执行任务解析样本，核对操作类型、目标表、追问、确认和写计划是否符合预期。 */
    @ParameterizedTest(name = "{0}")
    @MethodSource("goldenCases")
    void taskResolveMatchesGoldenExpectation(TaskEvalCase evalCase) {
        LlmGateway llmGateway = mock(LlmGateway.class);
        when(llmGateway.generateJson(any(LlmRequest.class))).thenReturn(evalCase.llmResponse());
        PromptTemplateService promptTemplateService =
                new PromptTemplateService(mock(PromptConfigMapper.class));
        AgentProperties properties = new AgentProperties();
        TaskResolveNode node = new TaskResolveNode(
                llmGateway, promptTemplateService, properties, new ContextAssembler(properties));

        Map<String, Object> initial = new LinkedHashMap<>();
        initial.put(WorkflowState.QUERY, evalCase.query());
        initial.put(WorkflowState.REWRITTEN_QUERY, "");
        initial.put(WorkflowState.MODEL, "eval-model");
        initial.put(WorkflowState.USER_ID, "eval-user");
        initial.put(WorkflowState.CONVERSATION_ID, "eval-" + evalCase.id());
        WorkflowState planned = mergedState(initial, node.apply(new WorkflowState(initial)));

        if (!evalCase.expected().errorContains().isBlank()) {
            assertThat(planned.error()).contains(evalCase.expected().errorContains());
            recordResult(evalCase, planned);
            return;
        }

        assertThat(planned.error()).isEmpty();
        assertThat(planned.taskOperation()).isEqualTo(evalCase.expected().operation());
        assertThat(planned.taskReadTarget()).isEqualTo(evalCase.expected().readTarget());
        assertThat(planned.taskIncludeTransactions())
                .isEqualTo(evalCase.expected().includeTransactions());
        assertThat(planned.taskIncludeQuotas()).isEqualTo(evalCase.expected().includeQuotas());
        assertThat(planned.taskClarify()).isEqualTo(evalCase.expected().clarify());
        assertThat(planned.taskConfirmationRequired())
                .isEqualTo(evalCase.expected().confirmationRequired());
        assertThat(planned.taskMissingSlots()).containsExactlyElementsOf(evalCase.expected().expectedMissing());

        if (evalCase.expected().expectedPlanTool().isBlank()) {
            assertThat(planned.taskPlan()).isEmpty();
        } else {
            assertThat(planned.taskPlan()).hasSize(1);
            Map<String, Object> step = planned.taskPlan().getFirst();
            assertThat(step.get("tool")).isEqualTo(evalCase.expected().expectedPlanTool());
            assertThat(mapValue(step.get("args"))).isEqualTo(evalCase.expected().expectedPlanArgs());
        }
        recordResult(evalCase, planned);
    }

    /** 所有样本执行完成后落盘逐条实际结果和汇总，供人工复核而不只看 JUnit 通过状态。 */
    @AfterAll
    static void writeEvaluationResults() throws IOException {
        Path outputDir = Path.of(System.getProperty(
                "task.eval.outputDir", "target/eval/task-resolve"));
        Files.createDirectories(outputDir);
        List<RecordedResult> ordered = RECORDED_RESULTS.stream()
                .sorted(Comparator.comparing(RecordedResult::id))
                .toList();
        StringBuilder jsonl = new StringBuilder();
        for (RecordedResult result : ordered) {
            jsonl.append(JsonUtil.toJson(result)).append('\n');
        }
        Files.writeString(outputDir.resolve("raw.jsonl"), jsonl,
                StandardCharsets.UTF_8);
        String summary = "# 任务测评结果\n\n"
                + "- 样本数: " + ordered.size() + "\n"
                + "- 通过: " + ordered.stream().filter(RecordedResult::passed).count() + "\n"
                + "- 结果文件: raw.jsonl\n";
        Files.writeString(outputDir.resolve("summary.md"), summary,
                StandardCharsets.UTF_8);
    }

    /** 为参数化测试提供全部可执行样本；资源缺失或解析失败直接让测试失败。 */
    private static Stream<TaskEvalCase> goldenCases() throws IOException {
        return loadCases().stream();
    }

    /** 从任务测评 JSONL 加载样本，跳过空行并带行号报告坏数据。 */
    private static List<TaskEvalCase> loadCases() throws IOException {
        InputStream input = TaskResolveEvalSetTest.class.getClassLoader()
                .getResourceAsStream(GOLDEN_RESOURCE);
        if (input == null) {
            throw new IllegalStateException("缺少任务测评资源: " + GOLDEN_RESOURCE);
        }
        List<TaskEvalCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            Set<String> ids = new HashSet<>();
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.trim().startsWith("#")) {
                    continue;
                }
                try {
                    TaskEvalCase evalCase = JsonUtil.fromJson(line, TaskEvalCase.class);
                    validateCase(evalCase, lineNo);
                    if (!ids.add(evalCase.id())) {
                        throw new IllegalStateException("重复 id: " + evalCase.id());
                    }
                    cases.add(evalCase);
                } catch (RuntimeException e) {
                    throw new IllegalStateException("任务测评集第 " + lineNo + " 行解析失败", e);
                }
            }
        }
        return cases;
    }

    /** 校验单条任务样本的关键字段，坏数据在加载阶段直接失败并指出行号。 */
    private static void validateCase(TaskEvalCase evalCase, int lineNo) {
        if (evalCase == null || evalCase.id() == null || evalCase.id().isBlank()) {
            throw new IllegalStateException("任务测评集第 " + lineNo + " 行缺少 id");
        }
        if (evalCase.category() == null || evalCase.category().isBlank()) {
            throw new IllegalStateException("任务测评集第 " + lineNo + " 行缺少 category");
        }
        if (evalCase.query() == null || evalCase.query().isBlank()) {
            throw new IllegalStateException("任务测评集第 " + lineNo + " 行缺少 query");
        }
        if (evalCase.llmResponse() == null || !evalCase.llmResponse().isObject()) {
            throw new IllegalStateException("任务测评集第 " + lineNo + " 行缺少对象型 llmResponse");
        }
        if (evalCase.expected() == null) {
            throw new IllegalStateException("任务测评集第 " + lineNo + " 行缺少 expected");
        }
    }

    /** 将节点增量合并回初始状态，模拟 LangGraph4j 节点间状态传递。 */
    private static WorkflowState mergedState(Map<String, Object> initial,
                                             Map<String, Object> updates) {
        Map<String, Object> merged = new LinkedHashMap<>(initial);
        merged.putAll(updates);
        return new WorkflowState(merged);
    }

    /** 将计划步骤中的 args 转成字符串键 Map，便于与 JSON 测评集做稳定比较。 */
    private static Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, val) -> result.put(String.valueOf(key), val));
        return result;
    }

    /** 把单条样本的期望和实际解析结果加入待落盘列表，指标与报告使用同一份事实。 */
    private static void recordResult(TaskEvalCase evalCase, WorkflowState actual) {
        Map<String, Object> expected = new LinkedHashMap<>();
        expected.put("operation", evalCase.expected().operation());
        expected.put("readTarget", evalCase.expected().readTarget());
        expected.put("includeTransactions", evalCase.expected().includeTransactions());
        expected.put("includeQuotas", evalCase.expected().includeQuotas());
        expected.put("clarify", evalCase.expected().clarify());
        expected.put("confirmationRequired", evalCase.expected().confirmationRequired());
        expected.put("missing", evalCase.expected().expectedMissing());
        expected.put("planTool", evalCase.expected().expectedPlanTool());
        expected.put("planArgs", evalCase.expected().expectedPlanArgs());
        expected.put("errorContains", evalCase.expected().errorContains());

        List<Map<String, Object>> plan = actual.taskPlan();
        Map<String, Object> actualView = new LinkedHashMap<>();
        actualView.put("operation", actual.taskOperation());
        actualView.put("readTarget", actual.taskReadTarget());
        actualView.put("includeTransactions", actual.taskIncludeTransactions());
        actualView.put("includeQuotas", actual.taskIncludeQuotas());
        actualView.put("clarify", actual.taskClarify());
        actualView.put("confirmationRequired", actual.taskConfirmationRequired());
        actualView.put("missing", actual.taskMissingSlots());
        actualView.put("planTool", plan.isEmpty() ? "" : plan.getFirst().get("tool"));
        actualView.put("planArgs", plan.isEmpty() ? Map.of() : mapValue(plan.getFirst().get("args")));
        actualView.put("error", actual.error());

        RECORDED_RESULTS.add(new RecordedResult(
                evalCase.id(), evalCase.category(), evalCase.query(), "PASS",
                expected, actualView));
    }

    /**
     * 单条任务测评样本：用户请求、模拟模型输出和标准答案。
     *
     * 使用模拟模型输出是为了让测评可重复；真实模型是否稳定地产生该结构属于线上质量评测，
     * 不在这个离线契约测试中混用。
     */
    record TaskEvalCase(String id, String category, String query,
                        JsonNode llmResponse, Expected expected) {
    }

    /** 单条样本的验收标准；errorContains 非空时只检查拒绝原因。 */
    record Expected(String operation, String readTarget,
                    boolean includeTransactions, boolean includeQuotas,
                    boolean clarify, boolean confirmationRequired,
                    List<String> expectedMissing,
                    String expectedPlanTool, Map<String, Object> expectedPlanArgs,
                    String errorContains) {

        /** 归一化字符串和集合，保证测试断言可以直接调用。 */
        Expected {
            operation = operation == null ? "" : operation;
            readTarget = readTarget == null ? "" : readTarget;
            expectedMissing = expectedMissing == null ? List.of() : List.copyOf(expectedMissing);
            expectedPlanTool = expectedPlanTool == null ? "" : expectedPlanTool;
            expectedPlanArgs = expectedPlanArgs == null ? Map.of() : Map.copyOf(expectedPlanArgs);
            errorContains = errorContains == null ? "" : errorContains;
        }
    }

    /** 单条落盘结果：保留问题、期望和实际返回值，避免结果文件只有统计数字。 */
    record RecordedResult(String id, String category, String query, String status,
                          Map<String, Object> expected, Map<String, Object> actual) {

        /** 当前记录只在全部断言通过后写入，因此 passed 由 status 统一表达。 */
        boolean passed() {
            return "PASS".equals(status);
        }
    }
}
