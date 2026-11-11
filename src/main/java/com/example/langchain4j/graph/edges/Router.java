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
    /** 检索无结果（换问法补查仍空/代码生成查空）→ 转人工节点 */
    public static final String TO_HANDOFF = "to_handoff";
    /** intent 为简历优化 → 简历任务触发节点（落草稿 + 提交后台异步执行，对话轮照常闭环） */
    public static final String TO_RESUME_TASK = "to_resume_task";

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

    /** 意图节点后：知识问答 → 问题分解（拆并列复合问题）；代码生成 → 直接检索（无需分解）；简历优化 → 触发节点；reject/异常 → 兜底 */
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
            // 步骤3：简历优化意图 → 触发节点（查档案 + 落草稿 + 提交后台异步执行，不进入检索/生成链路）
            if (IntentNode.INTENT_RESUME_OPTIMIZE.equals(state.intent())) {
                return TO_RESUME_TASK;
            }
            // 步骤4：知识问答意图 → 问题分解节点（拆并列复合问题，单问题原样放行）
            if (IntentNode.INTENT_KNOWLEDGE_QA.equals(state.intent())) {
                return TO_DECOMPOSE;
            }
            // 步骤5：reject（非法内容）或未知意图 → 兜底拒绝回答
            return TO_FALLBACK;
        };
    }

    /**
     * 检索节点后：知识问答 → 多跳判断（首轮空上下文也进，给换问法补查机会）；代码生成 → 空转人工/有资料直接生成。
     * <p>
     * 根因职责说明：条件边只能返回路由 key（框架 EdgeAction 返回 String，不能写状态），
     * 因此「首轮检索为空」的知识缺失预判（KNOWLEDGE_MISSING）由 RetrievalNode 首轮写入，
     * 换问法补查命中后由 HopCheckNode 覆盖为表述不当（EXPRESSION_ISSUE）；
     * 「空资料不许进生成」的最终闸门在 afterHopCheck（足够但上下文仍空 → 转人工）。
     */
    public EdgeAction<WorkflowState> afterRetrieval() {
        return state -> {
            // 步骤1：知识问答意图 → 多跳判断（首轮空上下文也进：换问法补查可能挽救，仍空由 afterHopCheck 闸门转人工）
            if (IntentNode.INTENT_KNOWLEDGE_QA.equals(state.intent())) {
                return TO_HOP_CHECK;
            }
            // 步骤2：代码生成空上下文 → 转人工（无改写补查环节，不进入 LLM 生成）
            if (state.context() == null || state.context().isBlank()) {
                return TO_HANDOFF;
            }
            // 步骤3：代码生成有资料 → 直接生成（无需多跳补全）
            return TO_GENERATE;
        };
    }

    /**
     * 多跳判断节点后：足够但上下文仍空 → 转人工（空资料不许进生成）；足够 → 生成；不足且未达上限 → 回检索（多跳环）。
     * 空上下文闸门兑住两类误判：换问法补查全部失败，或判断 LLM fail-open 在空上下文上误判足够。
     */
    public EdgeAction<WorkflowState> afterHopCheck() {
        return state -> {
            // 步骤1：足够但上下文仍空（补查失败/fail-open 误判）→ 转人工，空资料不许进生成
            if (state.hop().sufficient() && (state.context() == null || state.context().isBlank())) {
                return TO_HANDOFF;
            }
            // 步骤2：HopCheckNode 写入的显式判定标记（足够/超限 → 生成，路由只读标记避免逻辑重复）
            if (state.hop().sufficient()) {
                return TO_GENERATE;
            }
            // 步骤3：不足且未达上限 → 回检索节点，用下一跳查询补查
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
            if (state.validation().validated()) {
                return TO_FINALIZE;
            }
            // 步骤2：校验失败但重试次数未达上限 → 按失败原因分路由
            int maxRetries = Math.max(1, properties.getMaxValidateRetries());
            if (state.retryCount() < maxRetries) {
                // 2.1：质检发现回答与资料矛盾/编造（grounding）→ 回检索节点
                //      （RetrievalNode 会用失败原因改写 query 重新检索，换一批上下文再生成）
                if (ValidateNode.ISSUE_GROUNDING.equals(state.validation().issue())) {
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
