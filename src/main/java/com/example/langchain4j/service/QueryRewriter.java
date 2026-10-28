package com.example.langchain4j.service;

import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.repository.entity.Message;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 查询改写器（图外组件）：基于会话历史，把本轮 query 改写成不依赖历史也能理解的
 * 自包含句子，供意图识别、检索、生成共用。
 * <p>
 * 防御策略：
 * <ul>
 *   <li>无历史 → 短路，不调 LLM（改写与判断合并为一次调用，由模型自判是否需要改写）；</li>
 *   <li>LLM 失败或输出异常 → 降级返回原句（宁可失忆不可失败）；</li>
 *   <li>输出控制字符清洗 + 长度防御（防模型输出废话解释）。</li>
 * </ul>
 */
@Slf4j
@Service
public class QueryRewriter {

    /** 改写输出长度防御系数：输出超过原句 3 倍 + 200 字视为异常（大概率带了解释或废话） */
    private static final int OUTPUT_LENGTH_SLACK = 200;

    private final LlmGateway llmGateway;

    public QueryRewriter(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    /**
     * 改写本轮 query。
     *
     * @param query   本轮原始用户输入
     * @param history 最近 N 轮历史（正序，role/content 键值对，不含当前消息）
     * @param model   本轮使用的模型名
     * @return 改写后的自包含 query（无历史/失败时返回清洗后的原句）
     */
    public String rewrite(String query, List<Map<String, String>> history, String model) {
        // 步骤1：清洗原始 query（控制字符 + 首尾空白），空输入返回空串（entry 节点会兜底）
        String cleaned = clean(query);
        if (cleaned.isEmpty()) {
            return cleaned;
        }
        // 步骤2：无历史短路 —— 没有上下文可参考，改写无意义，省一次 LLM 调用
        if (history == null || history.isEmpty()) {
            return cleaned;
        }
        try {
            // 步骤3：LLM 判断 + 改写一步完成（prompt 要求无需改写时原样输出）
            String output = llmGateway.generate(model, PromptTemplates.REWRITE_SYSTEM,
                    buildUserPrompt(cleaned, history)).text();
            String rewritten = clean(output);
            // 步骤4：输出防御 —— 空输出或异常冗长（模型可能带解释废话）→ 降级原句
            if (rewritten.isEmpty() || rewritten.length() > cleaned.length() * 3 + OUTPUT_LENGTH_SLACK) {
                log.warn("[rewrite] invalid output length={}, fallback to original query",
                        rewritten.length());
                return cleaned;
            }
            return rewritten;
        } catch (Exception e) {
            // 步骤5：LLM 失败（超时/熔断等）→ 降级原句，不阻断主流程
            log.warn("[rewrite] llm failed, fallback to original query: {}", e.getMessage());
            return cleaned;
        }
    }

    /** 拼装改写 user prompt：历史（角色标注）+ 最新输入 */
    private String buildUserPrompt(String query, List<Map<String, String>> history) {
        StringBuilder sb = new StringBuilder("对话历史：\n");
        for (Map<String, String> item : history) {
            String content = item.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            String role = item.get("role");
            sb.append(Message.ROLE_USER.equals(role) ? "[用户] " : "[助手] ")
              .append(content).append('\n');
        }
        sb.append("\n用户最新输入：").append(query);
        return sb.toString();
    }

    /** 控制字符清洗 + 首尾空白去除（与 entry 节点清洗规则一致） */
    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
