package com.example.langchain4j.eval;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 执行器：逐条调用真实检索接口，计算关键词覆盖率并产出原始结果。
 *
 * 这里刻意不调用 LLM：检索是否命中是确定性判断，只有“失败原因归因”才交给验收 Agent。
 */
final class RetrievalEvalRunner {

    private static final Logger log = LoggerFactory.getLogger(RetrievalEvalRunner.class);
    private static final int SNIPPET_LIMIT = 300;

    private final EvalHttpClient httpClient;
    private final double minCoverage;

    /** 注入 HTTP 客户端和单条样本的通过阈值。 */
    RetrievalEvalRunner(EvalHttpClient httpClient, double minCoverage) {
        this.httpClient = httpClient;
        this.minCoverage = minCoverage;
    }

    /**
     * 顺序执行全部样本。
     *
     * 顺序执行而不是并发，是为了让限流、连接池和耗时指标更稳定；样本量扩大后再加并发度配置。
     */
    List<EvalResult> run(List<EvalCase> cases) {
        List<EvalResult> results = new ArrayList<>(cases.size());
        for (EvalCase evalCase : cases) {
            results.add(runOne(evalCase));
        }
        return results;
    }

    /**
     * 执行单条样本：命中任意检索块中的期望关键词即算该关键词命中。
     *
     * 单条失败不中断整批，错误写进结果，由验收阶段统一归因。
     */
    private EvalResult runOne(EvalCase evalCase) {
        long start = System.currentTimeMillis();
        try {
            EvalHttpClient.SearchHit hit = httpClient.search(evalCase.query(), evalCase.effectiveTopK());
            List<String> matched = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String keyword : evalCase.expectedKeywords()) {
                boolean found = hit.contents().stream()
                        .anyMatch(content -> containsIgnoreCase(content, keyword));
                if (found) {
                    matched.add(keyword);
                } else {
                    missing.add(keyword);
                }
            }
            double coverage = evalCase.expectedKeywords().isEmpty()
                    ? 0 : (double) matched.size() / evalCase.expectedKeywords().size();
            boolean passed = coverage >= minCoverage;
            List<String> snippets = hit.contents().stream()
                    .limit(evalCase.effectiveTopK())
                    .map(this::snippet)
                    .toList();
            log.info("[eval-runner] id={}, coverage={}/{}, passed={}, costMs={}",
                    evalCase.id(), matched.size(), evalCase.expectedKeywords().size(),
                    passed, System.currentTimeMillis() - start);
            return new EvalResult(evalCase.id(), evalCase.category(), evalCase.query(),
                    evalCase.effectiveTopK(), evalCase.expectedKeywords(), matched, missing,
                    coverage, passed, System.currentTimeMillis() - start, snippets, "");
        } catch (Exception e) {
            log.warn("[eval-runner] id={}, failed: {}", evalCase.id(), e.getMessage());
            return new EvalResult(evalCase.id(), evalCase.category(), evalCase.query(),
                    evalCase.effectiveTopK(), evalCase.expectedKeywords(), List.of(),
                    evalCase.expectedKeywords(), 0, false,
                    System.currentTimeMillis() - start, List.of(), e.getMessage());
        }
    }

    /** 关键词匹配统一小写；中文不受影响，英文可避免大小写误判。 */
    private boolean containsIgnoreCase(String content, String keyword) {
        return content != null && keyword != null
                && content.toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT));
    }

    /** 原始结果只保留片段，避免报告体积随知识块变大而失控。 */
    private String snippet(String content) {
        if (content == null) {
            return "";
        }
        String normalized = content.replaceAll("\\s+", " ").trim();
        return normalized.length() <= SNIPPET_LIMIT
                ? normalized : normalized.substring(0, SNIPPET_LIMIT) + "...[截断]";
    }

    /** 单条执行结果：既给验收 Agent 用，也原样写入 raw.jsonl。 */
    record EvalResult(String id,
                      String category,
                      String query,
                      int topK,
                      List<String> expectedKeywords,
                      List<String> matchedKeywords,
                      List<String> missingKeywords,
                      double coverage,
                      boolean passed,
                      long latencyMs,
                      List<String> retrievedSnippets,
                      String error) {
    }
}
