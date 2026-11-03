package com.example.langchain4j.service;

import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.repository.entity.Message;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * 查询改写器（图外组件）：基于会话历史，把本轮 query 改写成不依赖历史也能理解的
 * 自包含句子，同时判断本轮输入归属的话题段（与改写合并为一次 LLM 调用）。
 * <p>
 * 防御策略：
 * <ul>
 *   <li>无历史 → 短路，不调 LLM（改写与话题判断由段解析兜底）；</li>
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
    private final PromptTemplateService promptTemplateService;

    public QueryRewriter(LlmGateway llmGateway, PromptTemplateService promptTemplateService) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
    }

    /**
     * 改写本轮 query 并判断话题归属。
     *
     * @param query   本轮原始用户输入
     * @param history 最近 N 轮历史（正序，role/content 键值对，不含当前消息）
     * @param topics  会话已有话题标签列表（供归属判断，新话题则为新标签）
     * @param model   本轮使用的模型名
     * @return 改写结果（无历史/失败时降级为原句 + 不判断话题，段归属由调用方兜底）
     */
    public RewriteResult rewrite(String query, List<Map<String, String>> history,
                                 List<String> topics, String model) {
        // 步骤1：清洗原始 query（控制字符 + 首尾空白），空输入返回空串（entry 节点会兜底）
        String cleaned = clean(query);
        if (cleaned.isEmpty()) {
            return RewriteResult.degrade(cleaned);
        }
        // 步骤2：无历史短路 —— 没有上下文可参考，指代消解与话题归属都无意义，省一次 LLM 调用
        if (history == null || history.isEmpty()) {
            return RewriteResult.degrade(cleaned);
        }
        try {
            // 步骤3：LLM 话题归属 + 改写一步完成（网关封装：json_object 约束 + 截断感知 + 提取解析，不可用返回 null）
            // 提示词走配置化解析：DB 有覆盖用 DB 版，否则兑底代码默认值
            JsonNode node = llmGateway.generateJsonNode(model,
                    promptTemplateService.resolve("REWRITE_SYSTEM", PromptTemplates.REWRITE_SYSTEM),
                    buildUserPrompt(cleaned, history, topics), "rewrite");
            // 截断/解析失败 → 降级返回原句（宁可失忆不可失败）
            if (node == null) {
                return RewriteResult.degrade(cleaned);
            }
            // 步骤4：字段级容错（JsonNode 手取而非强类型反序列化），rewritten 与 topic 各自独立降级；
            //        字段缺失/类型错（如 newTopic 为字符串）不致整体失败：asText/asBoolean 对任何类型都安全
            // rewritten 防御：空或异常冗长（模型可能带解释废话）→ 降级原句
            String rewritten = clean(node.path("rewritten").asText(""));
            if (rewritten.isEmpty() || rewritten.length() > cleaned.length() * 3 + OUTPUT_LENGTH_SLACK) {
                log.warn("[rewrite] invalid rewritten length={}, fallback to original query", rewritten.length());
                rewritten = cleaned;
            }
            // topic 防御：清洗后为空则视为未判断，由调用方段解析兜底
            String topic = clean(node.path("topic").asText(""));
            return new RewriteResult(rewritten, topic, node.path("newTopic").asBoolean(false));
        } catch (Exception e) {
            // 步骤5：LLM 失败（超时/熔断等）→ 降级原句，不阻断主流程
            log.warn("[rewrite] llm failed, fallback to original query: {}", e.getMessage());
            return RewriteResult.degrade(cleaned);
        }
    }

    /** 拼装改写 user prompt：已有话题列表 + 历史（角色标注）+ 最新输入 */
    private String buildUserPrompt(String query, List<Map<String, String>> history, List<String> topics) {
        StringBuilder sb = new StringBuilder("已有话题列表：");
        if (topics == null || topics.isEmpty()) {
            sb.append("（无）");
        } else {
            sb.append(String.join("、", topics));
        }
        sb.append("\n对话历史：\n");
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
