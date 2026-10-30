package com.example.langchain4j.graph.edges;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.nodes.IntentNode;
import com.example.langchain4j.graph.nodes.ValidateNode;
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
    /** intent 有效意图 → 知识检索；validate 质检发现与资料矛盾（grounding）→ 回检索重查 */
    public static final String TO_RETRIEVAL = "to_retrieval";
    /** intent 为知识问答 → 问题分解（拆并列复合问题） */
    public static final String TO_DECOMPOSE = "to_decompose";
    /** retrieval 有上下文且知识问答意图 → 多跳判断（上下文完备性评估） */
    public static final String TO_HOP_CHECK = "to_hop_check";
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

    /** 意图节点后：知识问答 → 问题分解（拆并列复合问题）；代码生成 → 直接检索（无需分解）；reject/异常 → 兜底 */
    public EdgeAction<WorkflowState> afterIntent() {
        return state -> {
            // 步骤1：error 非空（理论不常见，意图节点已降级）→ 兜底
            if (state.error() != null && !state.error().isBlank()) {
                return TO_FALLBACK;
            }
            // 步骤2：代码生成意图 → 直接检索（无需分解，检索上下文对代码意图参考价值低）
            if (IntentNode.INTENT_CODE_GENERATE.equals(state.intent())) {
                return TO_RETRIEVAL;
            }
            // 步骤3：知识问答意图 → 问题分解节点（拆并列复合问题，单问题原样放行）
            if (IntentNode.INTENT_KNOWLEDGE_QA.equals(state.intent())) {
                return TO_DECOMPOSE;
            }
            // 步骤4：reject（非法内容）或未知意图 → 兜底拒绝回答
            return TO_FALLBACK;
        };
    }

    /**
     * 检索节点后：上下文为空 → 转人工；知识问答 → 多跳判断；代码生成 → 直接生成。
     * <p>
     * 根因职责说明：条件边只能返回路由 key（框架 EdgeAction 返回 String，不能写状态），
     * 因此「首轮检索为空」的知识缺失根因（KNOWLEDGE_MISSING）由 RetrievalNode 首轮写入，
     * 本路由只负责根据空上下文转人工；根因最终透出到响应与审计表。
     */
    public EdgeAction<WorkflowState> afterRetrieval() {
        return state -> {
            // 步骤1：上下文为空说明知识库无匹配（或检索降级）→ 转人工，不进入 LLM
            if (state.context() == null || state.context().isBlank()) {
                return TO_HANDOFF;
            }
            // 步骤2：知识问答意图 → 多跳判断（评估上下文是否足以回答，不足则补充检索）
            if (IntentNode.INTENT_KNOWLEDGE_QA.equals(state.intent())) {
                return TO_HOP_CHECK;
            }
            // 步骤3：代码生成意图 → 直接生成（无需多跳补全）
            return TO_GENERATE;
        };
    }

    /** 多跳判断节点后：上下文足够/超限 → 生成；不足且未达上限 → 回检索（多跳环） */
    public EdgeAction<WorkflowState> afterHopCheck() {
        return state -> {
            // 步骤1：HopCheckNode 写入的显式判定标记（足够/超限 → 生成，路由只读标记避免逻辑重复）
            if (state.hopSufficient()) {
                return TO_GENERATE;
            }
            // 步骤2：不足且未达上限 → 回检索节点，用下一跳查询补查
            return TO_RETRIEVAL;
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
            // 步骤2：校验失败但重试次数未达上限 → 按失败原因分路由
            int maxRetries = Math.max(1, properties.getMaxValidateRetries());
            if (state.retryCount() < maxRetries) {
                // 2.1：质检发现回答与资料矛盾/编造（grounding）→ 回检索节点
                //      （RetrievalNode 会用失败原因改写 query 重新检索，换一批上下文再生成）
                if (ValidateNode.ISSUE_GROUNDING.equals(state.validationIssue())) {
                    return TO_RETRIEVAL;
                }
                // 2.2：其余失败（格式问题等）→ 回生成节点重试
                return TO_GENERATE;
            }
            // 步骤3：重试次数超限 → 放弃重试，兜底收尾
            return TO_FALLBACK;
        };
    }
}
