package com.example.langchain4j.graph.qa.support;

import com.example.langchain4j.common.constant.ChatOptions;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.model.AnswerBudget;
import com.example.langchain4j.graph.qa.model.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import org.springframework.stereotype.Component;

/**
 * 回答预算解析器：把意图、查询复杂度和回答模式映射为统一预算。
 *
 * 优先级：deep、代码/闲聊、knowledge_qa simple/compound。
 * 复杂度来自 IntentPlanNode 的同一次规划调用，不在生成节点重复做规则判断。
 */
@Component
public class AnswerBudgetResolver {

    private static final String SIMPLE_STYLE =
            "先给结论，再列最多 3 条关键点；不复述问题，不展开无关背景。";
    private static final String COMPOUND_STYLE =
            "按子问题逐一回答，每个子问题先给结论并最多列 2 条依据；不复述资料原文。";
    private static final String DEEP_STYLE =
            "先给结论，再按需展开关键步骤、依据与边界条件；不要重复问题或堆砌无关信息。";
    private static final String CHITCHAT_STYLE =
            "用 1-2 句话简短自然回应，不要展开长篇说明。";

    private final AgentProperties properties;

    /** 注入回答预算配置；解析器只读配置，不持有会话状态。 */
    public AnswerBudgetResolver(AgentProperties properties) {
        this.properties = properties;
    }

    /** 解析本轮生成预算。 */
    public AnswerBudget resolve(WorkflowState state) {
        AgentProperties.AnswerBudget configured = properties.getLlm().getAnswerBudget();
        if (QaIntent.CODE_GENERATE.equals(state.intent())) {
            return profile(configured.getCode(), "");
        }
        if (QaIntent.CHITCHAT.equals(state.intent())) {
            return profile(configured.getChitchat(), CHITCHAT_STYLE);
        }
        if (QaIntent.isKnowledge(state.intent())) {
            if (ChatOptions.ANSWER_MODE_DEEP.equals(state.answerMode())) {
                return profile(configured.getKnowledgeDeep(), DEEP_STYLE);
            }
            if (isCompound(state)) {
                return profile(configured.getKnowledgeCompound(), COMPOUND_STYLE);
            }
            return profile(configured.getKnowledgeSimple(), SIMPLE_STYLE);
        }
        // 防御未知意图：按普通知识问答预算处理，避免无上限输出。
        return profile(configured.getKnowledgeSimple(), SIMPLE_STYLE);
    }

    /** 多子查询或显式 compound 视为复合问题，使用更宽的结构化回答预算。 */
    private boolean isCompound(WorkflowState state) {
        return "compound".equalsIgnoreCase(state.queryPlan().complexity())
                || state.queryPlan().subQueries().size() > 1;
    }

    /** 把配置档案转换为预算对象；档案缺失时退回 1024 token 的安全兜底。 */
    private AnswerBudget profile(AgentProperties.Profile profile, String style) {
        if (profile == null) {
            return new AnswerBudget(0, 1024, "");
        }
        return new AnswerBudget(profile.getMaxChars(), profile.getMaxOutputTokens(), style);
    }
}
