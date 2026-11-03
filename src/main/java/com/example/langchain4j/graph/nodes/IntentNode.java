package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
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
    private final PromptTemplateService promptTemplateService;

    public IntentNode(LlmGateway llmGateway, PromptTemplateService promptTemplateService) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        try {
            // 步骤1：调用 LLM 分类意图（网关封装：json_object 约束 + 截断感知 + 提取解析，不可用返回 null）；
            //        用有效查询（改写后的自包含句，未改写时即原始 query）
            // 提示词走配置化解析：DB 有覆盖用 DB 版，否则兑底代码默认值
            JsonNode node = llmGateway.generateJsonNode(state.model(),
                    promptTemplateService.resolve("INTENT_CLASSIFY_SYSTEM", PromptTemplates.INTENT_CLASSIFY_SYSTEM),
                    state.effectiveQuery(), "intent");
            // 步骤2：截断/解析失败 → 降级为默认意图（安全默认，不阻断主流程）
            if (node == null) {
                return Map.of(
                        WorkflowState.INTENT, INTENT_DEFAULT,
                        WorkflowState.INTENT_REASON, "输出截断或解析失败",
                        WorkflowState.ERROR, "");
            }
            // 步骤3：读取 intent 字段（缺失时用默认意图兜底）
            String intent = node.hasNonNull("intent")
                    ? node.get("intent").asText().trim()
                    : INTENT_DEFAULT;
            // 步骤4：白名单校验 —— 模型输出未知意图时降级为默认意图
            if (!VALID_INTENTS.contains(intent)) {
                log.warn("[intent] unknown intent '{}', fallback to default", intent);
                intent = INTENT_DEFAULT;
            }
            // 步骤5：读取 reason 字段（可空）
            String reason = node.hasNonNull("reason")
                    ? node.get("reason").asText()
                    : "";
            // 步骤6：返回增量更新：意图 + 理由 + 清空错误标记
            return Map.of(
                    WorkflowState.INTENT, intent,
                    WorkflowState.INTENT_REASON, reason,
                    WorkflowState.ERROR, "");
        } catch (BizException e) {
            // 步骤7：LLM 调用失败不阻断主流程 —— 降级为默认意图继续
            log.warn("[intent] llm failed, degrade to default intent: {}", e.getMessage());
            return Map.of(
                    WorkflowState.INTENT, INTENT_DEFAULT,
                    WorkflowState.INTENT_REASON, "模型降级",
                    WorkflowState.ERROR, "");
        } catch (Exception e) {
            // 步骤8：未知异常同样降级为默认意图
            log.warn("[intent] unexpected error, degrade to default intent: {}", e.getMessage());
            return Map.of(
                    WorkflowState.INTENT, INTENT_DEFAULT,
                    WorkflowState.INTENT_REASON, "解析降级",
                    WorkflowState.ERROR, "");
        }
    }
}
