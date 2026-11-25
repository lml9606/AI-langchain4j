package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.common.constant.ChatOptions;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.qa.model.QaIntent;
import com.example.langchain4j.graph.qa.support.EvidenceAssessor;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.model.LlmRequest;
import com.example.langchain4j.prompt.PromptTemplates;
import com.example.langchain4j.prompt.PromptTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 分层质量校验。规则层始终执行，检查空回答、过短和模型自曝错误；证据层只对知识问答
 * 检查回答对资料的词面支撑度；LLM 质检按需执行，只在高风险、低证据、deep、重试轮、
 * 上下文不完整或支撑不足时调用。
 *
 * 这样既保留防幻觉能力，又避免每个简单问题都多付一次 3~4 秒的 LLM 质检。
 */
@Slf4j
@Component
public class ValidateNode implements NodeAction<WorkflowState> {

    public static final String ISSUE_FORMAT = "format";
    public static final String ISSUE_GROUNDING = "grounding";

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final EvidenceAssessor evidenceAssessor;
    private final AgentProperties properties;

    public ValidateNode(LlmGateway llmGateway, PromptTemplateService promptTemplateService,
                        EvidenceAssessor evidenceAssessor, AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.evidenceAssessor = evidenceAssessor;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        if (!isValid(state.result(), state.intent())) {
            return fail(state, ISSUE_FORMAT, "输出为空、过于简短或格式错误");
        }
        if (!QaIntent.isKnowledge(state.intent())) {
            return pass(state, 1.0);
        }
        if (state.context().isBlank()) {
            return fail(state, ISSUE_GROUNDING, "知识问答缺少检索证据");
        }

        double support = evidenceAssessor.answerSupport(state.result(), state.context());
        if (!shouldLlmCheck(state, support)) {
            log.info("[validate] passed by rules, retryCount={}, support={}, confidence={}",
                    state.retryCount(), String.format(Locale.ROOT, "%.2f", support),
                    state.retrievalEvidence().confidence());
            return pass(state, Math.min(1.0, 0.75 + 0.25 * support));
        }

        QaResult qa = llmCheck(state);
        if (!qa.valid()) {
            return fail(state, qa.issue(), qa.reason());
        }
        log.info("[validate] passed by llm, retryCount={}, support={}",
                state.retryCount(), String.format(Locale.ROOT, "%.2f", support));
        return pass(state, 0.9);
    }

    /** 质检升级条件：只在高风险/低证据/深链路/重试或支撑不足时调用 LLM。 */
    private boolean shouldLlmCheck(WorkflowState state, double support) {
        return ChatOptions.ANSWER_MODE_DEEP.equals(state.answerMode())
                || state.retryCount() > 0
                || "high".equalsIgnoreCase(state.queryPlan().risk())
                || !state.retrievalEvidence().sufficient()
                || state.contextIncomplete()
                || support < properties.getRetrieval().getMinAnswerSupport();
    }

    /** 通过：写入质检结果、证据支撑置信度与最终置信度（取检索/支撑两者较小值）。 */
    private Map<String, Object> pass(WorkflowState state, double groundingConfidence) {
        double grounding = Math.max(0.0, Math.min(1.0, groundingConfidence));
        double retrieval = state.retrievalEvidence().confidence();
        double finalConfidence = retrieval <= 0.0 ? grounding : Math.min(retrieval, grounding);
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.VALIDATION, WorkflowState.Validation.of(true, "", ""));
        updates.put(WorkflowState.GROUNDING_CONFIDENCE, grounding);
        updates.put(WorkflowState.FINAL_CONFIDENCE, finalConfidence);
        return updates;
    }

    private Map<String, Object> fail(WorkflowState state, String issue, String reason) {
        int retries = state.retryCount() + 1;
        log.warn("[validate] failed, intent={}, issue={}, retryCount={}, resultLength={}",
                state.intent(), issue, retries, state.result() == null ? 0 : state.result().length());
        return Map.of(
                WorkflowState.RETRY_COUNT, retries,
                WorkflowState.VALIDATION, WorkflowState.Validation.of(false, issue, truncate(reason)));
    }

    private QaResult llmCheck(WorkflowState state) {
        String userPrompt = "用户需求：\n" + state.effectiveQuery()
                + "\n\n参考资料：\n" + state.context()
                + "\n\n候选回答：\n" + state.result();
        try {
            JsonNode node = llmGateway.generateJson(LlmRequest.of(state.model(),
                    promptTemplateService.resolve("VALIDATE_LLM_SYSTEM", PromptTemplates.VALIDATE_LLM_SYSTEM),
                    userPrompt).scene("validate"));
            if (node == null) {
                return QaResult.pass();
            }
            boolean valid = node.hasNonNull("valid") ? node.get("valid").asBoolean(false) : true;
            if (valid) {
                return QaResult.pass();
            }
            String issue = ISSUE_GROUNDING.equals(node.path("issue").asText(""))
                    ? ISSUE_GROUNDING : ISSUE_FORMAT;
            String reason = node.path("reason").asText("");
            log.warn("[validate] llm check failed, issue={}, reasonLength={}",
                    issue, reason == null ? 0 : reason.length());
            return new QaResult(false, issue, reason);
        } catch (Exception e) {
            log.warn("[validate] llm check degraded, pass: {}", e.getMessage());
            return QaResult.pass();
        }
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        int maxLength = properties.getLimits().getMaxValidationReasonLength();
        return text.length() <= maxLength ? text : text.substring(0, maxLength);
    }

    private boolean isValid(String result, String intent) {
        if (result == null || result.isBlank()) {
            return false;
        }
        String trimmed = result.trim();
        if (!QaIntent.CHITCHAT.equals(intent)
                && trimmed.length() < properties.getLimits().getMinResultLength()) {
            return false;
        }
        // 不做“致歉短语”黑名单：正常回答也可能礼貌表达不确定。
        // 真正的模型失败由生成节点抛错并走 fallback，不能靠关键词猜测。
        return true;
    }

    private record QaResult(boolean valid, String issue, String reason) {
        static QaResult pass() {
            return new QaResult(true, "", "");
        }
    }
}
