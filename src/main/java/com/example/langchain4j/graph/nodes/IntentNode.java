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
            // 步骤1：调用 LLM 分类意图（结构化输出，模型可能包裹说明文字，先提纯 JSON）；
            //        用有效查询（改写后的自包含句，未改写时即原始 query）
            String raw = llmGateway.generate(state.model(),
                    PromptTemplates.INTENT_CLASSIFY_SYSTEM, state.effectiveQuery()).text();
            JsonNode node = JsonUtil.fromJson(extractJson(raw), JsonNode.class);
            // 步骤2：读取 intent 字段（缺失时用默认意图兜底）
            String intent = node != null && node.hasNonNull("intent")
                    ? node.get("intent").asText().trim()
                    : INTENT_DEFAULT;
            // 步骤3：白名单校验 —— 模型输出未知意图时降级为默认意图
            if (!VALID_INTENTS.contains(intent)) {
                log.warn("[intent] unknown intent '{}', fallback to default", intent);
                intent = INTENT_DEFAULT;
            }
            // 步骤4：读取 reason 字段（可空）
            String reason = node != null && node.hasNonNull("reason")
                    ? node.get("reason").asText()
                    : "";
            // 步骤5：返回增量更新：意图 + 理由 + 清空错误标记
            return Map.of(
                    WorkflowState.INTENT, intent,
                    WorkflowState.INTENT_REASON, reason,
                    WorkflowState.ERROR, "");
        } catch (BizException e) {
            // 步骤6：LLM 调用失败不阻断主流程 —— 降级为默认意图继续
            log.warn("[intent] llm failed, degrade to default intent: {}", e.getMessage());
            return Map.of(
                    WorkflowState.INTENT, INTENT_DEFAULT,
                    WorkflowState.INTENT_REASON, "模型降级",
                    WorkflowState.ERROR, "");
        } catch (Exception e) {
            // 步骤7：JSON 解析等未知异常同样降级为默认意图
            log.warn("[intent] unexpected error, degrade to default intent: {}", e.getMessage());
            return Map.of(
                    WorkflowState.INTENT, INTENT_DEFAULT,
                    WorkflowState.INTENT_REASON, "解析降级",
                    WorkflowState.ERROR, "");
        }
    }

    /** 提取 JSON 片段：模型可能在 JSON 外包裹说明文字 */
    private String extractJson(String raw) {
        // 步骤1：空输入直接返回空串，交给上层解析降级
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // 步骤2：定位第一个 '{' 与最后一个 '}'，截取中间片段
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        // 步骤3：没有花括号则原样返回（上层解析失败会走降级）
        return raw;
    }
}
