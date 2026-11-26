package com.example.langchain4j.eval;

import com.example.langchain4j.common.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 出题 Agent：审计已有测评集，并按需生成同义改写样本。
 *
 * 默认只审计不扩题，因为用户已经维护了 golden 集；打开扩充后，它只改写 query，
 * 期望关键词和 topK 保持不变，避免模型把评测口径也改掉。
 */
final class EvalAuthorAgent {

    private static final Logger log = LoggerFactory.getLogger(EvalAuthorAgent.class);

    private final EvalConfig config;
    private final EvalLlmClient llmClient;

    /** 注入配置和 LLM 客户端；无 key 时只做确定性审计。 */
    EvalAuthorAgent(EvalConfig config, EvalLlmClient llmClient) {
        this.config = config;
        this.llmClient = llmClient;
    }

    /**
     * 审计测评集并可选扩充。
     *
     * @return 本次实际参与执行的样本 + 出题报告
     */
    AuthorOutcome prepare(List<EvalCase> goldenCases) {
        EvalCase.validate(goldenCases);
        List<EvalCase> cases = new ArrayList<>(goldenCases);
        StringBuilder report = new StringBuilder();
        report.append("# 出题 Agent 报告\n\n");
        report.append("- 输入样本: ").append(goldenCases.size()).append('\n');
        report.append("- 分类分布: ").append(categoryCounts(goldenCases)).append('\n');
        report.append("- 唯一 query: ").append(uniqueQueries(goldenCases)).append('\n');

        if (!config.authorEnabled()) {
            report.append("- 扩充: 关闭（使用已有测评集）\n");
            return new AuthorOutcome(cases, report.toString());
        }
        if (!config.llmAvailable()) {
            report.append("- 扩充: 跳过（未配置 EVAL_LLM_API_KEY）\n");
            return new AuthorOutcome(cases, report.toString());
        }

        int generated = 0;
        int skipped = 0;
        int sampleSize = Math.min(Math.max(1, config.authorSamples()), goldenCases.size());
        Set<String> queries = new HashSet<>();
        goldenCases.forEach(evalCase -> queries.add(normalize(evalCase.query())));
        for (int i = 0; i < sampleSize; i++) {
            EvalCase source = goldenCases.get(i);
            try {
                List<EvalCase> variants = paraphrase(source, i + 1, queries);
                cases.addAll(variants);
                generated += variants.size();
            } catch (Exception e) {
                skipped++;
                log.warn("[eval-author] 扩题失败, id={}, reason={}", source.id(), e.getMessage());
            }
        }
        report.append("- 扩充: 生成 ").append(generated)
                .append(" 条，失败 ").append(skipped).append(" 条\n");
        return new AuthorOutcome(cases, report.toString());
    }

    /**
     * 让模型把一条 query 改写成另一种自然问法。
     *
     * 只接受与原样本同分类、关键词非空且 query 未重复的变体；模型输出偏离 schema 时跳过。
     */
    private List<EvalCase> paraphrase(EvalCase source, int round, Set<String> existingQueries) {
        String system = """
                你是检索评测出题器。把用户给的 query 改写成另一种自然问法，必须保持语义不变，
                期望关键词和 topK 不变。只输出 JSON：
                {"cases":[{"query":"改写后的问题","expectedKeywords":["关键词1","关键词2"],"topK":5}]}
                不要解释，不要代码围栏。
                """;
        String user = "原样本：\nquery=" + source.query()
                + "\nexpectedKeywords=" + JsonUtil.toJson(source.expectedKeywords())
                + "\ntopK=" + source.effectiveTopK();
        JsonNode root = parseObject(llmClient.chat(system, user));
        List<EvalCase> variants = new ArrayList<>();
        int index = 0;
        for (JsonNode item : root.path("cases")) {
            String query = item.path("query").asText("").trim();
            if (query.isBlank() || !existingQueries.add(normalize(query))) {
                continue;
            }
            List<String> keywords = new ArrayList<>();
            for (JsonNode keyword : item.path("expectedKeywords")) {
                String text = keyword.asText("").trim();
                if (!text.isBlank()) {
                    keywords.add(text);
                }
            }
            if (keywords.isEmpty()) {
                continue;
            }
            index++;
            variants.add(new EvalCase(source.id() + "-p" + round + "-" + index,
                    source.category(), query, keywords, source.effectiveTopK()));
        }
        return variants;
    }

    /** 从模型输出里提取 JSON 对象；围栏/说明文字由 JsonUtil 统一剥离。 */
    private JsonNode parseObject(String raw) {
        String json = JsonUtil.extractJsonObject(raw);
        if (json.isBlank()) {
            throw new IllegalStateException("模型未返回 JSON 对象");
        }
        try {
            return JsonUtil.mapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("模型 JSON 解析失败: " + e.getMessage(), e);
        }
    }

    /** 分类分布文本：用 Map 保持插入顺序，报告更稳定。 */
    private String categoryCounts(List<EvalCase> cases) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (EvalCase evalCase : cases) {
            counts.merge(evalCase.category(), 1, Integer::sum);
        }
        return counts.toString();
    }

    /** 唯一 query 数：用于发现“同义重复题”导致的指标虚高。 */
    private long uniqueQueries(List<EvalCase> cases) {
        return cases.stream().map(EvalCase::query).map(this::normalize).distinct().count();
    }

    /** query 归一：去空白 + 小写，仅用于去重，不改变原始样本。 */
    private String normalize(String query) {
        return query == null ? "" : query.replaceAll("\\s+", "").toLowerCase();
    }

    /** 出题阶段产物：参与执行的样本 + 可读报告。 */
    record AuthorOutcome(List<EvalCase> cases, String report) {
    }
}
