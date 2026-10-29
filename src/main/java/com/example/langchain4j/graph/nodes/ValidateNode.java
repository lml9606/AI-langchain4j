package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 质量校验节点：对生成结果做两层质量门禁。
 * <ul>
 *   <li>规则层（零成本）：非空、最小长度、无模型自曝错误 —— 防硬错误；</li>
 *   <li>LLM 质检层（仅知识问答意图）：判断回答是否基于参考资料/是否答非所问 —— 防幻觉软错误，
 *       质检发现与资料矛盾（grounding）时由路由转回检索节点重查（Corrective RAG）。</li>
 * </ul>
 * 校验失败写入失败类型与原因，由路由决定回生成重试/回检索重查/兜底（有限重试环）。
 */
@Slf4j
@Component
public class ValidateNode implements NodeAction<WorkflowState> {

    private static final int MIN_RESULT_LENGTH = 5;
    /** 质检失败原因截断长度：防 LLM 输出超长原因占用状态与日志 */
    private static final int MAX_REASON_LENGTH = 200;

    /** 失败类型：规则层格式问题（路由回生成重试） */
    public static final String ISSUE_FORMAT = "format";
    /** 失败类型：LLM 质检发现编造/与资料矛盾（路由回检索重查） */
    public static final String ISSUE_GROUNDING = "grounding";

    private final LlmGateway llmGateway;

    public ValidateNode(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：规则层门禁（非空/最小长度/无模型自曝错误），失败 → 格式问题，回生成重试
        if (!isValid(state.result())) {
            return fail(state, ISSUE_FORMAT, "输出为空、过于简短或格式错误");
        }
        // 步骤2：LLM 质检层 —— 仅知识问答意图启用（有检索上下文可对照；代码生成难自动校验，跳过省一次调用）
        if (IntentNode.INTENT_KNOWLEDGE_QA.equals(state.intent())) {
            QaResult qa = llmCheck(state);
            if (!qa.valid()) {
                return fail(state, qa.issue(), qa.reason());
            }
        }
        // 步骤3：两层都过 → 写入通过标记，路由转收尾节点
        return Map.of(WorkflowState.VALIDATED, true);
    }

    /** 校验失败：重试计数 +1，写入失败类型与原因，交由路由判断回生成/回检索/兜底 */
    private Map<String, Object> fail(WorkflowState state, String issue, String reason) {
        int retries = state.retryCount() + 1;
        log.warn("[validate] failed, issue={}, retryCount={}, resultLength={}",
                issue, retries, state.result() == null ? 0 : state.result().length());
        return Map.of(
                WorkflowState.RETRY_COUNT, retries,
                WorkflowState.VALIDATED, false,
                WorkflowState.VALIDATION_ISSUE, issue,
                WorkflowState.VALIDATION_REASON, truncate(reason));
    }

    /** LLM 质检：输入用户需求 + 参考资料 + 候选回答，输出 {valid, issue, reason}；质检调用失败时放行（漏判优于误杀） */
    private QaResult llmCheck(WorkflowState state) {
        String userPrompt = "用户需求：\n" + state.effectiveQuery()
                + "\n\n参考资料：\n" + state.context()
                + "\n\n候选回答：\n" + state.result();
        try {
            String raw = llmGateway.generateJson(state.model(),
                    PromptTemplates.VALIDATE_LLM_SYSTEM, userPrompt).text();
            JsonNode node = JsonUtil.fromJson(extractJson(raw), JsonNode.class);
            if (node == null) {
                return QaResult.pass();
            }
            // valid 字段缺失视为放行（fail-open：漏判优于误杀）；issue 只认 grounding，其余回生成
            boolean valid = node.hasNonNull("valid") ? node.get("valid").asBoolean(false) : true;
            if (valid) {
                return QaResult.pass();
            }
            String issue = ISSUE_GROUNDING.equals(node.path("issue").asText(""))
                    ? ISSUE_GROUNDING : ISSUE_FORMAT;
            String reason = node.path("reason").asText("");
            log.warn("[validate] llm check failed, issue={}, reason={}", issue, reason);
            return new QaResult(false, issue, reason);
        } catch (Exception e) {
            // 质检 LLM 失败：fail-open 放行，不因质检故障阻断主流程
            log.warn("[validate] llm check degraded, pass: {}", e.getMessage());
            return QaResult.pass();
        }
    }

    /** 提取 JSON 主体：截取第一个 '{' 到最后一个 '}'，防御模型带 ```json 围栏或前后缀 */
    private String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("no json object in output");
        }
        return text.substring(start, end + 1);
    }

    private String truncate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_REASON_LENGTH ? text : text.substring(0, MAX_REASON_LENGTH);
    }

    private boolean isValid(String result) {
        // 规则1：空/空白结果直接判无效
        if (result == null || result.isBlank()) {
            return false;
        }
        // 规则2：去空白后长度低于最小阈值判无效（防模型只吐几个字）
        String trimmed = result.trim();
        if (trimmed.length() < MIN_RESULT_LENGTH) {
            return false;
        }
        // 规则3：模型自曝异常的输出（中/英道歉话术）视为无效
        return !trimmed.contains("抱歉，我暂时无法")
                && !trimmed.contains("I apologize");
    }

    /** LLM 质检结果（valid=true 时 issue/reason 为空串） */
    private record QaResult(boolean valid, String issue, String reason) {
        static QaResult pass() {
            return new QaResult(true, "", "");
        }
    }
}
