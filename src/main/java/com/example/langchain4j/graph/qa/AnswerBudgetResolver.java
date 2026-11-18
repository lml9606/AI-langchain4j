package com.example.langchain4j.graph.qa;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 回答预算解析器：把意图、查询复杂度和回答模式映射为统一预算。
 * <p>
 * 优先级：deep &gt; 代码/闲聊 &gt; knowledge_qa simple/compound。
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
    private static final String DETAIL_STYLE =
            "围绕上一轮回答逐点展开：先给结论，再补充原因、关键步骤和必要示例；不要另起无关话题。";
    private static final String CHITCHAT_STYLE =
            "用 1-2 句话简短自然回应，不要展开长篇说明。";
    private static final List<String> DETAIL_FOLLOW_UP_MARKERS = List.of(
            "详细说说", "详细讲", "详细一点", "展开说说", "展开讲", "展开一下",
            "具体说说", "具体讲", "具体点", "再多说", "再说说", "继续说",
            "举个例子", "深入说说", "深入讲");

    private final AgentProperties properties;

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
            if ("deep".equals(state.answerMode())) {
                return profile(configured.getKnowledgeDeep(), DEEP_STYLE);
            }
            // 明确要求展开上一轮回答：用中间档预算，避免“详细说说”仍按简单问答被截短
            if (isDetailFollowUp(state)) {
                return profile(configured.getKnowledgeCompound(), DETAIL_STYLE);
            }
            if (isCompound(state)) {
                return profile(configured.getKnowledgeCompound(), COMPOUND_STYLE);
            }
            return profile(configured.getKnowledgeSimple(), SIMPLE_STYLE);
        }
        // 防御未知意图：按普通知识问答预算处理，避免无上限输出。
        return profile(configured.getKnowledgeSimple(), SIMPLE_STYLE);
    }

    private boolean isCompound(WorkflowState state) {
        return "compound".equalsIgnoreCase(state.queryPlan().complexity())
                || state.queryPlan().subQueries().size() > 1;
    }

    /** 追问展开：只在有历史可展开时提升预算，避免孤立的一句“详细说说”被放大。 */
    private boolean isDetailFollowUp(WorkflowState state) {
        if (state.history().isEmpty()) {
            return false;
        }
        String query = state.query();
        if (query == null || query.isBlank()) {
            return false;
        }
        for (String marker : DETAIL_FOLLOW_UP_MARKERS) {
            if (query.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private AnswerBudget profile(AgentProperties.Profile profile, String style) {
        if (profile == null) {
            return new AnswerBudget(0, 1024, "");
        }
        return new AnswerBudget(profile.getMaxChars(), profile.getMaxOutputTokens(), style);
    }
}
