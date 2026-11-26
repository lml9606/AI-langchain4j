package com.example.langchain4j.graph.qa.edges;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.model.QaIntent;
import com.example.langchain4j.graph.qa.nodes.ValidateNode;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.model.TaskOperation;
import com.example.langchain4j.graph.task.react.ReactProtocol;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 路由测评：验证问答、任务、ReAct、确认和质检分支不会走错高风险路径。 */
class RouterTest {

    private Router router;

    /** 每个用例使用独立配置，便于验证重试上限等路由条件。 */
    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.setMaxValidateRetries(3);
        router = new Router(properties);
    }

    /** 入口空输入或清洗失败必须兜底，正常输入才进入意图规划。 */
    @Test
    void entryRoutesErrorsToFallback() throws Exception {
        assertThat(router.afterEntry().apply(state(Map.of(
                WorkflowState.ERROR, "empty input"))))
                .isEqualTo(Router.TO_FALLBACK);
        assertThat(router.afterEntry().apply(state(Map.of(WorkflowState.ERROR, ""))))
                .isEqualTo(Router.TO_INTENT_PLAN);
    }

    /** 意图规划必须把知识问答、任务、闲聊和拒绝分别送到唯一目标。 */
    @Test
    void intentPlanRoutesByIntent() throws Exception {
        assertThat(router.afterIntentPlan().apply(state(Map.of(
                WorkflowState.INTENT, QaIntent.KNOWLEDGE_QA))))
                .isEqualTo(Router.TO_RETRIEVAL_PROBE);
        assertThat(router.afterIntentPlan().apply(state(Map.of(
                WorkflowState.INTENT, QaIntent.TASK))))
                .isEqualTo(Router.TO_TASK_RESOLVE);
        assertThat(router.afterIntentPlan().apply(state(Map.of(
                WorkflowState.INTENT, QaIntent.CHITCHAT))))
                .isEqualTo(Router.TO_GENERATE);
        assertThat(router.afterIntentPlan().apply(state(Map.of(
                WorkflowState.INTENT, QaIntent.REJECT))))
                .isEqualTo(Router.TO_FALLBACK);
    }

    /** 探针证据不足、复杂计划或 deep 模式必须升级完整检索。 */
    @Test
    void retrievalProbeRoutesByEvidenceAndMode() throws Exception {
        WorkflowState sufficient = state(Map.of(
                WorkflowState.INTENT, QaIntent.KNOWLEDGE_QA,
                WorkflowState.CONTEXT, "有资料",
                WorkflowState.RETRIEVAL_EVIDENCE, evidence(true),
                WorkflowState.QUERY_PLAN, simplePlan()));
        WorkflowState insufficient = state(Map.of(
                WorkflowState.INTENT, QaIntent.KNOWLEDGE_QA,
                WorkflowState.CONTEXT, "弱资料",
                WorkflowState.RETRIEVAL_EVIDENCE, evidence(false),
                WorkflowState.QUERY_PLAN, simplePlan()));
        WorkflowState deep = state(Map.of(
                WorkflowState.INTENT, QaIntent.KNOWLEDGE_QA,
                WorkflowState.CONTEXT, "有资料",
                WorkflowState.RETRIEVAL_EVIDENCE, evidence(true),
                WorkflowState.QUERY_PLAN, simplePlan(),
                WorkflowState.ANSWER_MODE, "deep"));

        assertThat(router.afterRetrievalProbe().apply(sufficient)).isEqualTo(Router.TO_GENERATE);
        assertThat(router.afterRetrievalProbe().apply(insufficient)).isEqualTo(Router.TO_RETRIEVAL_FULL);
        assertThat(router.afterRetrievalProbe().apply(deep)).isEqualTo(Router.TO_RETRIEVAL_FULL);
    }

    /** 完整检索仍为空时知识问答转人工，代码生成允许无资料继续生成。 */
    @Test
    void fullRetrievalRoutesEmptyKnowledgeToHandoff() throws Exception {
        assertThat(router.afterRetrievalFull().apply(state(Map.of(
                WorkflowState.INTENT, QaIntent.KNOWLEDGE_QA,
                WorkflowState.CONTEXT, ""))))
                .isEqualTo(Router.TO_HANDOFF);
        assertThat(router.afterRetrievalFull().apply(state(Map.of(
                WorkflowState.INTENT, QaIntent.CODE_GENERATE,
                WorkflowState.CONTEXT, ""))))
                .isEqualTo(Router.TO_GENERATE);
    }

    /** 任务解析必须缺参先收尾、写操作只进确认节点，不能让 WRITE 直达执行节点。 */
    @Test
    void taskResolveRoutesByOperationAndClarification() throws Exception {
        assertThat(router.afterTaskResolve().apply(state(Map.of(
                WorkflowState.TASK_CLARIFY, true))))
                .isEqualTo(Router.TO_FINALIZE);
        assertThat(router.afterTaskResolve().apply(state(Map.of(
                WorkflowState.TASK_CLARIFY, false,
                WorkflowState.TASK_OPERATION, TaskOperation.READ.name()))))
                .isEqualTo(Router.TO_TASK_READ);
        assertThat(router.afterTaskResolve().apply(state(Map.of(
                WorkflowState.TASK_CLARIFY, false,
                WorkflowState.TASK_OPERATION, TaskOperation.WRITE.name()))))
                .isEqualTo(Router.TO_TASK_CONFIRM);
        assertThat(router.afterTaskResolve().apply(state(Map.of(
                WorkflowState.TASK_CLARIFY, false,
                WorkflowState.TASK_OPERATION, TaskOperation.REACT.name()))))
                .isEqualTo(Router.TO_REACT_PLAN);
    }

    /** 第一段读取只有在拿到来源主键时才能继续关联查询，否则直接回答。 */
    @Test
    void taskReadRoutesFollowUpOnlyWithSourceId() throws Exception {
        assertThat(router.afterTaskRead().apply(state(Map.of(
                WorkflowState.TASK_INCLUDE_QUOTAS, true,
                WorkflowState.TASK_SOURCE_ID, "E1001"))))
                .isEqualTo(Router.TO_TASK_READ_QUOTA);
        assertThat(router.afterTaskRead().apply(state(Map.of(
                WorkflowState.TASK_INCLUDE_TRANSACTIONS, true,
                WorkflowState.TASK_SOURCE_ID, "Q1001"))))
                .isEqualTo(Router.TO_TASK_READ_TRANSACTION);
        assertThat(router.afterTaskRead().apply(state(Map.of(
                WorkflowState.TASK_INCLUDE_QUOTAS, true,
                WorkflowState.TASK_SOURCE_ID, ""))))
                .isEqualTo(Router.TO_TASK_ANSWER);
    }

    /** ReAct 只有明确 call_tool 才进工具节点，结束或未知动作转回答，配置错误兜底。 */
    @Test
    void reactPlanRoutesOnlyExplicitToolCalls() throws Exception {
        assertThat(router.afterReactPlan().apply(state(Map.of(
                WorkflowState.REACT_DONE, false,
                WorkflowState.REACT_ACTION, Map.of(
                        ReactProtocol.FIELD_ACTION, ReactProtocol.ACTION_CALL_TOOL)))))
                .isEqualTo(Router.TO_REACT_TOOL);
        assertThat(router.afterReactPlan().apply(state(Map.of(
                WorkflowState.REACT_DONE, true))))
                .isEqualTo(Router.TO_TASK_ANSWER);
        assertThat(router.afterReactPlan().apply(state(Map.of(
                WorkflowState.ERROR, "bad state"))))
                .isEqualTo(Router.TO_FALLBACK);
    }

    /** ReAct 工具执行后未结束时回到规划，结束时回答，图级错误兜底。 */
    @Test
    void reactToolRoutesContinuationAndStop() throws Exception {
        assertThat(router.afterReactTool().apply(state(Map.of(
                WorkflowState.REACT_DONE, false))))
                .isEqualTo(Router.TO_REACT_PLAN);
        assertThat(router.afterReactTool().apply(state(Map.of(
                WorkflowState.REACT_DONE, true))))
                .isEqualTo(Router.TO_TASK_ANSWER);
        assertThat(router.afterReactTool().apply(state(Map.of(
                WorkflowState.ERROR, "bad state"))))
                .isEqualTo(Router.TO_FALLBACK);
    }

    /** 生成和任务确认只要产生错误就必须兜底，成功路径才进入下一节点。 */
    @Test
    void generateAndConfirmRouteErrorsToFallback() throws Exception {
        assertThat(router.afterGenerate().apply(state(Map.of(
                WorkflowState.ERROR, "llm down"))))
                .isEqualTo(Router.TO_FALLBACK);
        assertThat(router.afterGenerate().apply(state(Map.of(WorkflowState.ERROR, ""))))
                .isEqualTo(Router.TO_VALIDATE);
        assertThat(router.afterTaskConfirm().apply(state(Map.of(
                WorkflowState.ERROR, "redis down"))))
                .isEqualTo(Router.TO_FALLBACK);
        assertThat(router.afterTaskConfirm().apply(state(Map.of(WorkflowState.ERROR, ""))))
                .isEqualTo(Router.TO_FINALIZE);
    }

    /** 质检通过收尾，grounding 失败重查，格式错误重生成，重试耗尽兜底。 */
    @Test
    void validateRoutesByIssueAndRetryBudget() throws Exception {
        assertThat(router.afterValidate().apply(state(Map.of(
                WorkflowState.VALIDATION, WorkflowState.Validation.of(true, "", "")))))
                .isEqualTo(Router.TO_FINALIZE);
        assertThat(router.afterValidate().apply(state(Map.of(
                WorkflowState.RETRY_COUNT, 1,
                WorkflowState.VALIDATION,
                WorkflowState.Validation.of(false, ValidateNode.ISSUE_GROUNDING, "weak")))))
                .isEqualTo(Router.TO_RETRIEVAL_FULL);
        assertThat(router.afterValidate().apply(state(Map.of(
                WorkflowState.RETRY_COUNT, 1,
                WorkflowState.VALIDATION,
                WorkflowState.Validation.of(false, ValidateNode.ISSUE_FORMAT, "bad")))))
                .isEqualTo(Router.TO_GENERATE);
        assertThat(router.afterValidate().apply(state(Map.of(
                WorkflowState.RETRY_COUNT, 3,
                WorkflowState.VALIDATION,
                WorkflowState.Validation.of(false, ValidateNode.ISSUE_FORMAT, "bad")))))
                .isEqualTo(Router.TO_FALLBACK);
    }

    /** 构造带默认错误空串的状态，避免测试只关注当前分支。 */
    private WorkflowState state(Map<String, Object> values) {
        java.util.LinkedHashMap<String, Object> data = new java.util.LinkedHashMap<>();
        data.put(WorkflowState.ERROR, "");
        data.putAll(values);
        return new WorkflowState(data);
    }

    /** 构造简单查询计划和测试证据。 */
    private WorkflowState.QueryPlan simplePlan() {
        return new WorkflowState.QueryPlan(
                "JVM", java.util.List.of("JVM"), "simple", "low", false, "simple");
    }

    /** 构造充分或不足的检索证据。 */
    private WorkflowState.RetrievalEvidence evidence(boolean sufficient) {
        return new WorkflowState.RetrievalEvidence(
                sufficient ? 2 : 1, 0.03, sufficient ? 0.8 : 0.1,
                sufficient ? 0.8 : 0.2, sufficient, sufficient ? "ok" : "weak");
    }
}
