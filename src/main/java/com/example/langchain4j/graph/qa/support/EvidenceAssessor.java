package com.example.langchain4j.graph.qa.support;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.knowledge.model.KnowledgeRetrievalResult;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Set;

/**
 * 检索证据评估器：把“有没有检索到、检索结果覆盖了多少问题词、融合分有多高”
 * 统一折算成可解释的证据置信度。
 *
 * 不采信模型自报的 confidence，置信度来自检索结果本身；不按问题长短做快慢判断，
 * 只判断证据是否足够；阈值配置化，后续用离线验证集校准，代码里不写死业务口径。
 */
@Component
public class EvidenceAssessor {

    private static final Pattern LATIN_TOKEN = Pattern.compile("[a-z0-9][a-z0-9_+#.-]*");
    private static final Pattern HAN_RUN = Pattern.compile("[\\p{IsHan}]+");
    private static final int MAX_QUERY_TOKENS = 64;

    private final AgentProperties properties;

    /** 注入证据阈值配置；评估器无状态，可被并发复用。 */
    public EvidenceAssessor(AgentProperties properties) {
        this.properties = properties;
    }

    /**
     * 评估一次检索是否足以回答用户问题。
     *
     * @param query  当前检索查询（主查询）
     * @param result 检索上下文、命中数、融合最高分
     * @return 证据评估（置信度 0~1 + 是否充分 + 可解释原因）
     */
    public WorkflowState.RetrievalEvidence assess(String query, KnowledgeRetrievalResult result) {
        if (result == null || !result.hasContext()) {
            return WorkflowState.RetrievalEvidence.empty();
        }
        double coverage = keywordCoverage(query, result.context());
        double breadth = Math.min(1.0, result.hitCount() / 3.0);
        double scoreScale = Math.max(0.001, properties.getRetrieval().getRrfScoreScale());
        double scoreNorm = Math.min(1.0, result.topScore() / scoreScale);
        // 覆盖率是主信号：命中的父块必须覆盖问题中的实体/关键词；命中数和融合分作为辅助信号。
        double confidence = clamp(0.60 * coverage + 0.25 * breadth + 0.15 * scoreNorm);
        boolean sufficient = coverage >= properties.getRetrieval().getMinKeywordCoverage()
                && confidence >= properties.getRetrieval().getMinEvidenceConfidence();
        String reason = String.format(Locale.ROOT,
                "hits=%d, topScore=%.4f, coverage=%.2f, confidence=%.2f",
                result.hitCount(), result.topScore(), coverage, confidence);
        return new WorkflowState.RetrievalEvidence(
                result.hitCount(), result.topScore(), coverage, confidence, sufficient, reason);
    }

    /**
     * 回答对资料的词面支撑度：用于质检分层，低支撑时再升级到 LLM 质检。
     * 该值只表示“回答中的关键词有多少能在资料中找到”，不替代语义质检。
     */
    public double answerSupport(String answer, String context) {
        if (answer == null || answer.isBlank() || context == null || context.isBlank()) {
            return 0.0;
        }
        return keywordCoverage(answer, context);
    }

    /** 查询词覆盖率：英文/数字按词，中文按二元组；去重后最多取 64 个 token。 */
    double keywordCoverage(String query, String content) {
        Set<String> tokens = tokens(query);
        if (tokens.isEmpty()) {
            return content == null || content.isBlank() ? 0.0 : 0.5;
        }
        String haystack = content == null ? "" : content.toLowerCase(Locale.ROOT);
        int matched = 0;
        for (String token : tokens) {
            if (haystack.contains(token)) {
                matched++;
            }
        }
        return (double) matched / tokens.size();
    }

    /** 中英文混合 token 提取；中文二元组比单字更能表达语义，也避免长句切词依赖。 */
    private Set<String> tokens(String text) {
        Set<String> tokens = new LinkedHashSet<>();
        if (text == null || text.isBlank()) {
            return tokens;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        Matcher latin = LATIN_TOKEN.matcher(normalized);
        while (latin.find() && tokens.size() < MAX_QUERY_TOKENS) {
            tokens.add(latin.group());
        }
        Matcher han = HAN_RUN.matcher(normalized);
        while (han.find() && tokens.size() < MAX_QUERY_TOKENS) {
            String run = han.group();
            if (run.length() == 1) {
                tokens.add(run);
                continue;
            }
            for (int i = 0; i < run.length() - 1 && tokens.size() < MAX_QUERY_TOKENS; i++) {
                tokens.add(run.substring(i, i + 2));
            }
        }
        return tokens;
    }

    /** 把任意分值裁剪到 0~1，避免配置或异常输入把置信度带出边界。 */
    private double clamp(double value) {
        return Math.max(0.0, Math.min(1.0, value));
    }
}
