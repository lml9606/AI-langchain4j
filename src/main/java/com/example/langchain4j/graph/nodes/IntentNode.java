package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

/**
 * 意图识别节点：LLM 结构化输出三类意图。
 * 防御策略：LLM 失败或 JSON 解析失败 → 默认 knowledge_qa（安全默认，不阻断主流程）；
 * 识别为非法内容 → reject（走兜底拒绝回答）。
 */
@Slf4j
@Component
public class IntentNode implements NodeAction<WorkflowState> {

    public static final String INTENT_CODE_GENERATE = "code_generate";
    public static final String INTENT_KNOWLEDGE_QA = "knowledge_qa";
    public static final String INTENT_REJECT = "reject";
    public static final String INTENT_DEFAULT = INTENT_KNOWLEDGE_QA;

    private static final Set<String> VALID_INTENTS =
            Set.of(INTENT_CODE_GENERATE, INTENT_KNOWLEDGE_QA, INTENT_REJECT);

    private final LlmGateway llmGateway;

    public IntentNode(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        try {
            String raw = llmGateway.generate(PromptTemplates.INTENT_CLASSIFY_SYSTEM, state.query()).text();
            JsonNode node = JsonUtil.fromJson(extractJson(raw), JsonNode.class);
            String intent = node != null && node.hasNonNull("intent")
                    ? node.get("intent").asText().trim()
                    : INTENT_DEFAULT;
            if (!VALID_INTENTS.contains(intent)) {
                log.warn("[intent] unknown intent '{}', fallback to default", intent);
                intent = INTENT_DEFAULT;
            }
            String reason = node != null && node.hasNonNull("reason")
                    ? node.get("reason").asText()
                    : "";
            return Map.of(
                    WorkflowState.INTENT, intent,
                    WorkflowState.INTENT_REASON, reason,
                    WorkflowState.ERROR, "");
        } catch (BizException e) {
            // 意图识别失败不阻断：降级为默认意图继续主流程
            log.warn("[intent] llm failed, degrade to default intent: {}", e.getMessage());
            return Map.of(
                    WorkflowState.INTENT, INTENT_DEFAULT,
                    WorkflowState.INTENT_REASON, "模型降级",
                    WorkflowState.ERROR, "");
        } catch (Exception e) {
            log.warn("[intent] unexpected error, degrade to default intent: {}", e.getMessage());
            return Map.of(
                    WorkflowState.INTENT, INTENT_DEFAULT,
                    WorkflowState.INTENT_REASON, "解析降级",
                    WorkflowState.ERROR, "");
        }
    }

    /** 提取 JSON 片段：模型可能在 JSON 外包裹说明文字 */
    private String extractJson(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        return raw;
    }
}
