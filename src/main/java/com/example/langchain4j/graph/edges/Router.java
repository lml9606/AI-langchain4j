package com.example.langchain4j.graph.edges;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.nodes.IntentNode;
import com.example.langchain4j.graph.state.WorkflowState;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * 条件路由：决定节点执行后的下一跳。
 * 路由 key 与 {@link com.example.langchain4j.graph.AgentGraphBuilder} 中的 mapping 对应。
 */
@Component
public class Router {

    // ---- 路由 key：条件边返回的通道名，与 AgentGraphBuilder 中的 mapping 一一对应 ----
    /** entry 校验通过 → 意图识别 */
    public static final String TO_INTENT = "to_intent";
    /** intent 有效意图 → 知识检索 */
    public static final String TO_RETRIEVAL = "to_retrieval";
    /** validate 失败且未达重试上限 → 回生成节点（有限重试环） */
    public static final String TO_GENERATE = "to_generate";
    /** generate 成功 → 质量校验 */
    public static final String TO_VALIDATE = "to_validate";
    /** 校验失败/LLM 异常/reject → 兜底节点 */
    public static final String TO_FALLBACK = "to_fallback";
    /** validate 通过 → 收尾节点 */
    public static final String TO_FINALIZE = "to_finalize";
    /** 知识库检索无结果 → 转人工节点 */
    public static final String TO_HANDOFF = "to_handoff";

    private final AgentProperties properties;

    public Router(AgentProperties properties) {
        this.properties = properties;
    }

    /** 守卫节点后：输入校验失败（空输入）直接兜底，跳过意图识别，省一次 LLM 调用 */
    public EdgeAction<WorkflowState> afterEntry() {
        return state -> {
            // 步骤1：error 非空说明守卫校验失败（如空输入）→ 直接兜底
            if (state.error() != null && !state.error().isBlank()) {
                return TO_FALLBACK;
            }
            // 步骤2：校验通过 → 进入意图识别
            return TO_INTENT;
        };
    }

    /** 意图节点后：有效意图走检索，reject/异常走兜底 */
    public EdgeAction<WorkflowState> afterIntent() {
        return state -> {
            // 步骤1：error 非空（理论不常见，意图节点已降级）→ 兜底
            if (state.error() != null && !state.error().isBlank()) {
                return TO_FALLBACK;
            }
            // 步骤2：代码生成/知识问答两类有效意图 → 检索
            String intent = state.intent();
            if (IntentNode.INTENT_CODE_GENERATE.equals(intent)
                    || IntentNode.INTENT_KNOWLEDGE_QA.equals(intent)) {
                return TO_RETRIEVAL;
            }
            // 步骤3：reject（非法内容）或未知意图 → 兜底拒绝回答
            return TO_FALLBACK;
        };
    }

    /** 检索节点后：上下文为空（无命中/检索降级）→ 转人工；有结果 → 生成 */
    public EdgeAction<WorkflowState> afterRetrieval() {
        return state -> {
            // 步骤1：上下文为空说明知识库无匹配（或检索降级）→ 转人工，不进入 LLM
            if (state.context() == null || state.context().isBlank()) {
                return TO_HANDOFF;
            }
            // 步骤2：有检索上下文 → 进入生成节点
            return TO_GENERATE;
        };
    }

    /** 生成节点后：异常走兜底，正常走校验 */
    public EdgeAction<WorkflowState> afterGenerate() {
        return state -> {
            // 步骤1：error 非空说明 LLM 生成失败 → 兜底
            if (state.error() != null && !state.error().isBlank()) {
                return TO_FALLBACK;
            }
            // 步骤2：生成成功 → 进入质量校验
            return TO_VALIDATE;
        };
    }

    /** 校验节点后：通过→收尾；失败且未达上限→重试生成；超限→兜底 */
    public EdgeAction<WorkflowState> afterValidate() {
        return state -> {
            // 步骤1：读取 ValidateNode 写入的显式判定标记（路由只读标记，避免逻辑重复）
            if (state.validated()) {
                return TO_FINALIZE;
            }
            // 步骤2：校验失败但重试次数未达上限 → 回生成节点重试
            int maxRetries = Math.max(1, properties.getMaxValidateRetries());
            if (state.retryCount() < maxRetries) {
                return TO_GENERATE;
            }
            // 步骤3：重试次数超限 → 放弃重试，兜底收尾
            return TO_FALLBACK;
        };
    }
}
