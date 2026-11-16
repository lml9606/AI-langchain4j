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
import java.util.regex.Pattern;

/**
 * 意图识别节点：两级路由 —— 规则快速通道（零 LLM 成本、毫秒级）+ LLM 结构化输出四类意图。
 * <ul>
 *   <li>规则层：纯寒暄短句（问候/致谢/告别等）直接命中 chitchat，不进 LLM；
 *       规则保守只收“短且纯寒暄”，带业务诉求的长句（如“你好，帮我查退款政策”）不命中，交给 LLM；</li>
 *   <li>LLM 层：结构化输出 code_generate / knowledge_qa / resume_optimize / chitchat / reject；</li>
 * </ul>
 * 防御策略：LLM 失败或 JSON 解析失败 → 默认 knowledge_qa（安全默认，不阻断主流程）；
 * 识别为非法内容 → reject（走兜底拒绝回答）。
 */
@Slf4j
@Component
public class IntentNode implements NodeAction<WorkflowState> {

    public static final String INTENT_CODE_GENERATE = "code_generate";
    public static final String INTENT_KNOWLEDGE_QA = "knowledge_qa";
    public static final String INTENT_RESUME_OPTIMIZE = "resume_optimize";
    public static final String INTENT_CHITCHAT = "chitchat";
    public static final String INTENT_REJECT = "reject";
    public static final String INTENT_DEFAULT = INTENT_KNOWLEDGE_QA;

    private static final Set<String> VALID_INTENTS =
            Set.of(INTENT_CODE_GENERATE, INTENT_KNOWLEDGE_QA, INTENT_RESUME_OPTIMIZE,
                    INTENT_CHITCHAT, INTENT_REJECT);

    /**
     * 寒暄规则（生产级保守策略）：纯寒暄短句才命中，防误伤“寒暄+业务诉求”的混合句。
     * 长度阈值 12：长句一律交给 LLM 语义分类。
     */
    private static final int CHITCHAT_MAX_LENGTH = 12;
    private static final Pattern CHITCHAT_PATTERN = Pattern.compile(
            "^(你好|你好呀|您好|hi|hello|嗨|嗨喽|哈喽|hey|在吗|在不在|有人在吗|有人吗|" +
                    "谢谢|谢谢你|感谢|多谢|再见|拜拜|bye|早上好|上午好|中午好|下午好|晚上好|晚安|早)[\\s!！~～。.，,?？]*$",
            Pattern.CASE_INSENSITIVE);

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;

    public IntentNode(LlmGateway llmGateway, PromptTemplateService promptTemplateService) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤0：规则快速通道 —— 纯寒暄短句直接判为闲聊，省一次 LLM 调用（企业级两级路由惯例）
        String effective = state.effectiveQuery();
        if (isChitchatByRule(effective)) {
            log.info("[intent] chitchat matched by rule: {}", effective);
            return Map.of(
                    WorkflowState.INTENT, INTENT_CHITCHAT,
                    WorkflowState.INTENT_REASON, "规则匹配：寒暄",
                    WorkflowState.ERROR, "");
        }
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

    /**
     * 寒暄规则匹配：短（≤CHITCHAT_MAX_LENGTH）且整体命中寒暄词表才返回 true。
     * “你好，帮我查退款政策”这类带业务诉求的长句不命中，交给 LLM 分类。
     */
    private boolean isChitchatByRule(String query) {
        if (query == null || query.isBlank() || query.length() > CHITCHAT_MAX_LENGTH) {
            return false;
        }
        return CHITCHAT_PATTERN.matcher(query.trim()).matches();
    }
}
