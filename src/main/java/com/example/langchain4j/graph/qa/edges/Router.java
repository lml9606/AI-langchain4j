package com.example.langchain4j.graph.qa.edges;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.qa.nodes.ValidateNode;
import com.example.langchain4j.graph.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * 条件路由：决定节点执行后的下一跳。
 * 路由 key 与 {@link com.example.langchain4j.graph.AgentGraphBuilder} 中的 mapping 对应。
 */
@Slf4j
@Component
public class Router {

    /** entry 校验通过 → 意图与检索规划 */
    public static final String TO_INTENT_PLAN = "to_intent_plan";
    /** 知识问答/代码生成 → 轻量检索探针 */
    public static final String TO_RETRIEVAL_PROBE = "to_retrieval_probe";
    /** 证据不足/复杂问题/质检重查 → 完整检索 */
    public static final String TO_RETRIEVAL_FULL = "to_retrieval_full";
    /** validate 失败且未达重试上限 → 回生成节点 */
    public static final String TO_GENERATE = "to_generate";
    /** generate 成功 → 分层质量校验 */
    public static final String TO_VALIDATE = "to_validate";
    /** 校验失败/LLM 异常/reject → 兜底节点 */
    public static final String TO_FALLBACK = "to_fallback";
    /** validate 通过 → 收尾节点 */
    public static final String TO_FINALIZE = "to_finalize";
    /** 完整检索仍无证据 → 转人工节点 */
    public static final String TO_HANDOFF = "to_handoff";
    /** intent 为任务型请求 → 任务规划节点 */
    public static final String TO_TASK_PLAN = "to_task_plan";
    /** 计划可执行 → 工具执行节点 */
    public static final String TO_TASK_EXECUTE = "to_task_execute";
    /** 计划包含写工具 → 确认节点 */
    public static final String TO_TASK_CONFIRM = "to_task_confirm";

    private final AgentProperties properties;

    public Router(AgentProperties properties) {
        this.properties = properties;
    }

    /** 统一记录路由决策与证据信号，便于排查“为什么走了快/慢链路”。 */
    private String route(String stage, WorkflowState state, String target) {
        WorkflowState.RetrievalEvidence evidence = state.retrievalEvidence();
        log.info("[route] stage={}, intent={}, depth={}, planFull={}, evidenceConfidence={}, sufficient={}, error={}, target={}",
                stage, state.intent(), state.retrievalDepth(), state.queryPlan().requiresFullRetrieval(),
                evidence.confidence(), evidence.sufficient(),
                state.error() != null && !state.error().isBlank(), target);
        return target;
    }

    /** 守卫节点后：空输入直接兜底，其他进入意图与规划。 */
    public EdgeAction<WorkflowState> afterEntry() {
        return state -> state.error() != null && !state.error().isBlank()
                ? route("entry", state, TO_FALLBACK)
                : route("entry", state, TO_INTENT_PLAN);
    }

    /** 意图规划后：按意图分流；知识问答与代码生成进入检索探针。 */
    public EdgeAction<WorkflowState> afterIntentPlan() {
        return state -> {
            if (state.error() != null && !state.error().isBlank()) {
                return route("intent-plan", state, TO_FALLBACK);
            }
            if (QaIntent.CODE_GENERATE.equals(state.intent())
                    || QaIntent.KNOWLEDGE_QA.equals(state.intent())) {
                return route("intent-plan", state, TO_RETRIEVAL_PROBE);
            }
            if (QaIntent.TASK.equals(state.intent())) {
                return route("intent-plan", state, TO_TASK_PLAN);
            }
            if (QaIntent.CHITCHAT.equals(state.intent())) {
                return route("intent-plan", state, TO_GENERATE);
            }
            return route("intent-plan", state, TO_FALLBACK);
        };
    }

    /** 探针后：证据充分且计划简单 → 直接生成；否则最多升级一次完整检索。 */
    public EdgeAction<WorkflowState> afterRetrievalProbe() {
        return state -> {
            if (!QaIntent.KNOWLEDGE_QA.equals(state.intent())) {
                // 代码生成不依赖知识库命中：有资料可参考，无资料也直接生成。
                return route("retrieval-probe", state, TO_GENERATE);
            }
            boolean needsFull = state.context().isBlank()
                    || "deep".equals(state.answerMode())
                    || state.queryPlan().requiresFullRetrieval()
                    || !state.retrievalEvidence().sufficient();
            return route("retrieval-probe", state, needsFull ? TO_RETRIEVAL_FULL : TO_GENERATE);
        };
    }

    /** 完整检索后：仍无证据 → 转人工；有证据（即使不完整）→ 生成并声明边界。 */
    public EdgeAction<WorkflowState> afterRetrievalFull() {
        return state -> {
            if (QaIntent.KNOWLEDGE_QA.equals(state.intent()) && state.context().isBlank()) {
                return route("retrieval-full", state, TO_HANDOFF);
            }
            return route("retrieval-full", state, TO_GENERATE);
        };
    }

    /** 任务规划后：缺参追问 → 直接收尾；规划失败 → 兜底；含写工具 → 确认；只读计划 → 执行工具。 */
    public EdgeAction<WorkflowState> afterTaskPlan() {
        return state -> {
            if (state.taskClarify()) {
                return route("task-plan", state, TO_FINALIZE);
            }
            if (state.error() != null && !state.error().isBlank()) {
                return route("task-plan", state, TO_FALLBACK);
            }
            if (state.taskConfirmationRequired()) {
                return route("task-plan", state, TO_TASK_CONFIRM);
            }
            return route("task-plan", state, TO_TASK_EXECUTE);
        };
    }

    /** 确认节点后：令牌创建失败 → 兜底；成功 → 收尾并把 pendingAction 透出到响应。 */
    public EdgeAction<WorkflowState> afterTaskConfirm() {
        return state -> {
            if (state.error() != null && !state.error().isBlank()) {
                return route("task-confirm", state, TO_FALLBACK);
            }
            return route("task-confirm", state, TO_FINALIZE);
        };
    }

    /** 生成节点后：异常走兜底，正常走分层校验。 */
    public EdgeAction<WorkflowState> afterGenerate() {
        return state -> {
            if (state.error() != null && !state.error().isBlank()) {
                return route("generate", state, TO_FALLBACK);
            }
            return route("generate", state, TO_VALIDATE);
        };
    }

    /** 校验节点后：通过→收尾；grounding 失败→完整重查；格式失败→重生成；超限→兜底。 */
    public EdgeAction<WorkflowState> afterValidate() {
        return state -> {
            if (state.validation().validated()) {
                return route("validate", state, TO_FINALIZE);
            }
            int maxRetries = Math.max(1, properties.getMaxValidateRetries());
            if (state.retryCount() < maxRetries) {
                if (ValidateNode.ISSUE_GROUNDING.equals(state.validation().issue())) {
                    return route("validate", state, TO_RETRIEVAL_FULL);
                }
                return route("validate", state, TO_GENERATE);
            }
            return route("validate", state, TO_FALLBACK);
        };
    }
}
