package com.example.langchain4j.service;

import com.example.langchain4j.common.util.JsonUtil;
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

    public QueryRewriter(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
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
            // 步骤3：LLM 话题归属 + 改写一步完成（json_object 模式约束输出，prompt 要求严格 JSON）
            String output = llmGateway.generateJson(model, PromptTemplates.REWRITE_SYSTEM,
                    buildUserPrompt(cleaned, history, topics)).text();
            return parse(output, cleaned);
        } catch (Exception e) {
            // 步骤4：LLM 失败（超时/熔断等）→ 降级原句，不阻断主流程
            log.warn("[rewrite] llm failed, fallback to original query: {}", e.getMessage());
            return RewriteResult.degrade(cleaned);
        }
    }

    /** 解析 LLM 输出的 JSON：字段级容错（JsonNode 手取而非强类型反序列化），rewritten 与 topic 各自独立降级 */
    private RewriteResult parse(String output, String original) {
        String cleanedOut = clean(output);
        if (cleanedOut.isEmpty()) {
            return RewriteResult.degrade(original);
        }
        try {
            JsonNode node = JsonUtil.fromJson(extractJson(cleanedOut), JsonNode.class);
            if (node == null) {
                return RewriteResult.degrade(original);
            }
            // 字段缺失/类型错（如 newTopic 为字符串）不致整体失败：asText/asBoolean 对任何类型都安全
            // rewritten 防御：空或异常冗长（模型可能带解释废话）→ 降级原句
            String rewritten = clean(node.path("rewritten").asText(""));
            if (rewritten.isEmpty() || rewritten.length() > original.length() * 3 + OUTPUT_LENGTH_SLACK) {
                log.warn("[rewrite] invalid rewritten length={}, fallback to original query", rewritten.length());
                rewritten = original;
            }
            // topic 防御：清洗后为空则视为未判断，由调用方段解析兜底
            String topic = clean(node.path("topic").asText(""));
            return new RewriteResult(rewritten, topic, node.path("newTopic").asBoolean(false));
        } catch (Exception e) {
            log.warn("[rewrite] parse output failed, fallback to original query: {}", e.getMessage());
            return RewriteResult.degrade(original);
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
