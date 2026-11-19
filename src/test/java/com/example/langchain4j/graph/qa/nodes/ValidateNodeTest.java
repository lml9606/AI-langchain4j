package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.graph.qa.EvidenceAssessor;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.service.PromptTemplateService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 质量校验节点单测：两层门禁（规则层 + LLM 质检层）与 fail-open 防御策略。
 */
@ExtendWith(MockitoExtension.class)
class ValidateNodeTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private LlmGateway llmGateway;
    @Mock
    private PromptTemplateService promptTemplateService;
    @Mock
    private EvidenceAssessor evidenceAssessor;

    private ValidateNode node;

    @BeforeEach
    void setUp() {
        node = new ValidateNode(llmGateway, promptTemplateService, evidenceAssessor, new AgentProperties());
        // 配置化解析默认兑底；规则层直接拒绝的用例不经过 LLM（lenient 容忍未使用的桩）
        lenient().when(promptTemplateService.resolve(anyString(), anyString()))
                .thenAnswer(invocation -> invocation.getArgument(1));
    }

    private WorkflowState stateOf(String result, String intent) {
        return new WorkflowState(Map.of(
                WorkflowState.RESULT, result,
                WorkflowState.INTENT, intent,
                WorkflowState.QUERY, "问题",
                WorkflowState.CONTEXT, "参考资料"));
    }

    private WorkflowState groundedStateOf(String result) {
        return new WorkflowState(Map.of(
                WorkflowState.RESULT, result,
                WorkflowState.INTENT, QaIntent.KNOWLEDGE_QA,
                WorkflowState.QUERY, "JVM 内存模型",
                WorkflowState.CONTEXT, "JVM 内存模型包含堆、栈、方法区。",
                WorkflowState.QUERY_PLAN, new WorkflowState.QueryPlan(
                        "JVM 内存模型", java.util.List.of("JVM 内存模型"),
                        "simple", "low", false, "simple"),
                WorkflowState.RETRIEVAL_EVIDENCE, new WorkflowState.RetrievalEvidence(
                        2, 0.03, 0.8, 0.8, true, "ok")));
    }

    private WorkflowState.Validation validationOf(Map<String, Object> updates) {
        return (WorkflowState.Validation) updates.get(WorkflowState.VALIDATION);
    }

    @Test
    @DisplayName("规则层：空结果 → 格式问题，回生成重试")
    void ruleLayerRejectsEmptyResult() {
        Map<String, Object> updates = node.apply(stateOf("", QaIntent.CODE_GENERATE));

        WorkflowState.Validation validation = validationOf(updates);
        assertThat(validation.validated()).isFalse();
        assertThat(validation.issue()).isEqualTo(ValidateNode.ISSUE_FORMAT);
        assertThat(updates.get(WorkflowState.RETRY_COUNT)).isEqualTo(1);
    }

    @Test
    @DisplayName("LLM 质检：判定编造（grounding）→ 失败标记，路由回检索重查")
    void llmCheckRejectsUngroundedAnswer() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class)))
                .thenReturn(MAPPER.readTree(
                        "{\"valid\":false,\"issue\":\"grounding\",\"reason\":\"回答与参考资料矛盾\"}"));

        Map<String, Object> updates =
                node.apply(stateOf("这是一段足够长的正常回答内容", QaIntent.KNOWLEDGE_QA));

        WorkflowState.Validation validation = validationOf(updates);
        assertThat(validation.validated()).isFalse();
        assertThat(validation.issue()).isEqualTo(ValidateNode.ISSUE_GROUNDING);
    }

    @Test
    @DisplayName("质检 LLM 不可用 → fail-open 放行（漏判优于误杀）")
    void failOpenWhenJudgeUnavailable() {
        when(llmGateway.generateJson(any(LlmRequest.class)))
                .thenReturn(null);

        Map<String, Object> updates =
                node.apply(stateOf("这是一段足够长的正常回答内容", QaIntent.KNOWLEDGE_QA));

        assertThat(validationOf(updates).validated()).isTrue();
    }

    @Test
    @DisplayName("规则层 + LLM 质检都通过 → 写入通过标记")
    void passWhenBothLayersOk() throws Exception {
        when(llmGateway.generateJson(any(LlmRequest.class)))
                .thenReturn(MAPPER.readTree("{\"valid\":true}"));

        Map<String, Object> updates =
                node.apply(stateOf("这是一段足够长的正常回答内容", QaIntent.KNOWLEDGE_QA));

        WorkflowState.Validation validation = validationOf(updates);
        assertThat(validation.validated()).isTrue();
        assertThat(validation.issue()).isEmpty();
    }

    @Test
    @DisplayName("高证据 + 低风险 + 高支撑 → 只走规则层，跳过多余 LLM 质检")
    void skipLlmWhenEvidenceIsStrong() {
        when(evidenceAssessor.answerSupport(
                "JVM 内存模型包含堆和栈", "JVM 内存模型包含堆、栈、方法区。"))
                .thenReturn(0.8);

        Map<String, Object> updates = node.apply(
                groundedStateOf("JVM 内存模型包含堆和栈"));

        WorkflowState.Validation validation = (WorkflowState.Validation) updates.get(WorkflowState.VALIDATION);
        assertThat(validation.validated()).isTrue();
        assertThat(updates.get(WorkflowState.FINAL_CONFIDENCE)).isEqualTo(0.8);
        verifyNoInteractions(llmGateway);
    }
}
