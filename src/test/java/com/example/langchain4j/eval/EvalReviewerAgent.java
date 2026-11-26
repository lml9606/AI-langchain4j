package com.example.langchain4j.eval;

import com.example.langchain4j.common.util.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 验收 Agent：先做确定性门禁，再按需让 LLM 对失败项归因。
 *
 * 门禁（通过率/平均覆盖率/错误数）永远由代码判断；LLM 只负责解释“为什么失败”，
 * 这样即使模型给出错误判断，也不会把硬门槛放过去。
 */
final class EvalReviewerAgent {

    private static final Logger log = LoggerFactory.getLogger(EvalReviewerAgent.class);
    private static final int MAX_LLM_ANALYSES = 10;

    private final EvalConfig config;
    private final EvalLlmClient llmClient;

    /** 注入配置和 LLM 客户端；无 key 时自动退化为规则归因。 */
    EvalReviewerAgent(EvalConfig config, EvalLlmClient llmClient) {
        this.config = config;
        this.llmClient = llmClient;
    }

    /**
     * 汇总执行结果并给出最终验收结论。
     *
     * @return 是否通过、统计摘要、Markdown 报告、失败归因
     */
    Verdict review(List<RetrievalEvalRunner.EvalResult> results) {
        int total = results.size();
        int passed = (int) results.stream().filter(RetrievalEvalRunner.EvalResult::passed).count();
        int errors = (int) results.stream()
                .filter(result -> result.error() != null && !result.error().isBlank()).count();
        double avgCoverage = results.stream()
                .mapToDouble(RetrievalEvalRunner.EvalResult::coverage)
                .average().orElse(0);
        double passRate = total == 0 ? 0 : (double) passed / total;
        long p95 = p95(results);

        boolean pass = errors == 0
                && passRate >= config.minPassRate()
                && avgCoverage >= config.minAvgCoverage();

        List<RetrievalEvalRunner.EvalResult> failures = results.stream()
                .filter(result -> !result.passed())
                .toList();
        List<EvalAnalysis> analyses = analyse(failures);
        String report = render(total, passed, errors, avgCoverage, passRate, p95, pass, results, analyses);
        Stats stats = new Stats(total, passed, errors, passRate, avgCoverage, p95);
        return new Verdict(pass, report, analyses, stats);
    }

    /**
     * 失败归因：优先 LLM，失败或未启用时使用规则。
     *
     * 只分析前 10 条失败，避免一次跑几百条时把 token 预算打爆。
     */
    private List<EvalAnalysis> analyse(List<RetrievalEvalRunner.EvalResult> failures) {
        if (failures.isEmpty()) {
            return List.of();
        }
        List<RetrievalEvalRunner.EvalResult> sample = failures.stream()
                .limit(MAX_LLM_ANALYSES).toList();
        if (config.reviewEnabled() && config.llmAvailable()) {
            try {
                return llmAnalyse(sample);
            } catch (Exception e) {
                log.warn("[eval-reviewer] LLM 归因失败，改用规则归因: {}", e.getMessage());
            }
        }
        return ruleAnalyse(sample);
    }

    /** LLM 归因：把失败样本压成 JSON，要求模型输出固定 cause 枚举。 */
    private List<EvalAnalysis> llmAnalyse(List<RetrievalEvalRunner.EvalResult> failures) {
        String system = """
                你是检索评测验收器。根据失败样本判断原因，cause 只能是：
                RETRIEVAL_MISS（检索没召回正确内容）、
                KEYWORD_TOO_STRICT（关键词过严或过细）、
                DATA_MISSING（知识库没有对应内容）、
                RUNTIME_ERROR（接口/服务异常）。
                只输出 JSON：{"analyses":[{"id":"...","cause":"...","reason":"...","suggestion":"..."}]}
                不要解释，不要代码围栏。
                """;
        String user = "失败样本：\n" + JsonUtil.toJson(failures);
        String raw = llmClient.chat(system, user);
        String json = JsonUtil.extractJsonObject(raw);
        if (json.isBlank()) {
            throw new IllegalStateException("验收模型未返回 JSON");
        }
        JsonNode root;
        try {
            root = JsonUtil.mapper().readTree(json);
        } catch (Exception e) {
            throw new IllegalStateException("验收模型 JSON 解析失败: " + e.getMessage(), e);
        }
        List<EvalAnalysis> analyses = new ArrayList<>();
        for (JsonNode item : root.path("analyses")) {
            analyses.add(new EvalAnalysis(item.path("id").asText(""),
                    item.path("cause").asText("UNKNOWN"),
                    item.path("reason").asText(""),
                    item.path("suggestion").asText("")));
        }
        return analyses;
    }

    /** 规则归因：没有 LLM 时的兜底，保证流水线仍能给出可操作结论。 */
    private List<EvalAnalysis> ruleAnalyse(List<RetrievalEvalRunner.EvalResult> failures) {
        List<EvalAnalysis> analyses = new ArrayList<>();
        for (RetrievalEvalRunner.EvalResult result : failures) {
            String cause;
            String reason;
            String suggestion;
            if (result.error() != null && !result.error().isBlank()) {
                cause = "RUNTIME_ERROR";
                reason = result.error();
                suggestion = "先检查评测环境是否可用、Token 是否过期、检索服务是否报错";
            } else if (result.retrievedSnippets().isEmpty()) {
                cause = "DATA_MISSING";
                reason = "检索返回为空";
                suggestion = "确认该分类的文档已导入，且文档未过期/未被删除";
            } else if (result.missingKeywords().size() == result.expectedKeywords().size()) {
                cause = "RETRIEVAL_MISS";
                reason = "有召回结果，但期望关键词全部未命中";
                suggestion = "检查分块粒度、向量模型和 query 改写是否把问题带偏";
            } else {
                cause = "KEYWORD_TOO_STRICT";
                reason = "只命中部分关键词";
                suggestion = "检查期望关键词是否过细，或答案被拆到多个块中";
            }
            analyses.add(new EvalAnalysis(result.id(), cause, reason, suggestion));
        }
        return analyses;
    }

    /** 渲染 Markdown 报告：统计、门禁、分类明细、失败归因和建议。 */
    private String render(int total, int passed, int errors, double avgCoverage,
                          double passRate, long p95, boolean pass,
                          List<RetrievalEvalRunner.EvalResult> results,
                          List<EvalAnalysis> analyses) {
        StringBuilder report = new StringBuilder();
        report.append("# 检索评测验收报告\n\n");
        report.append("## 结论\n\n");
        report.append("- 最终结果: ").append(pass ? "PASS" : "FAIL").append('\n');
        report.append("- 样本数: ").append(total).append('\n');
        report.append("- 通过: ").append(passed).append('\n');
        report.append("- 通过率: ").append(percent(passRate)).append('\n');
        report.append("- 平均覆盖率: ").append(percent(avgCoverage)).append('\n');
        report.append("- P95 耗时: ").append(p95).append("ms\n");
        report.append("- 执行错误: ").append(errors).append('\n');
        report.append("\n## 门禁\n\n");
        report.append("| 门禁 | 阈值 | 实际 | 结果 |\n|---|---|---|---|\n");
        report.append("| 通过率 | ").append(percent(config.minPassRate())).append(" | ")
                .append(percent(passRate)).append(" | ")
                .append(passRate >= config.minPassRate() ? "PASS" : "FAIL").append(" |\n");
        report.append("| 平均覆盖率 | ").append(percent(config.minAvgCoverage())).append(" | ")
                .append(percent(avgCoverage)).append(" | ")
                .append(avgCoverage >= config.minAvgCoverage() ? "PASS" : "FAIL").append(" |\n");
        report.append("| 执行错误 | 0 | ").append(errors).append(" | ")
                .append(errors == 0 ? "PASS" : "FAIL").append(" |\n");

        report.append("\n## 分类明细\n\n");
        report.append("| 分类 | 样本 | 通过 | 通过率 | 平均覆盖率 |\n|---|---|---|---|---|\n");
        for (Map.Entry<String, List<RetrievalEvalRunner.EvalResult>> entry : byCategory(results).entrySet()) {
            List<RetrievalEvalRunner.EvalResult> list = entry.getValue();
            long categoryPassed = list.stream().filter(RetrievalEvalRunner.EvalResult::passed).count();
            double categoryCoverage = list.stream()
                    .mapToDouble(RetrievalEvalRunner.EvalResult::coverage).average().orElse(0);
            report.append("| ").append(entry.getKey()).append(" | ").append(list.size())
                    .append(" | ").append(categoryPassed)
                    .append(" | ").append(percent((double) categoryPassed / list.size()))
                    .append(" | ").append(percent(categoryCoverage)).append(" |\n");
        }

        report.append("\n## 失败归因\n\n");
        if (analyses.isEmpty()) {
            report.append("无失败样本。\n");
        } else {
            report.append("| id | cause | reason | suggestion |\n|---|---|---|---|\n");
            for (EvalAnalysis analysis : analyses) {
                report.append("| ").append(escape(analysis.id()))
                        .append(" | ").append(analysis.cause())
                        .append(" | ").append(escape(analysis.reason()))
                        .append(" | ").append(escape(analysis.suggestion())).append(" |\n");
            }
        }
        report.append("\n## 建议\n\n");
        report.append(recommendation(pass, analyses));
        return report.toString();
    }

    /** 按分类聚合，保持报告中分类顺序稳定。 */
    private Map<String, List<RetrievalEvalRunner.EvalResult>> byCategory(
            List<RetrievalEvalRunner.EvalResult> results) {
        Map<String, List<RetrievalEvalRunner.EvalResult>> grouped = new LinkedHashMap<>();
        for (RetrievalEvalRunner.EvalResult result : results) {
            grouped.computeIfAbsent(result.category(), key -> new ArrayList<>()).add(result);
        }
        return grouped;
    }

    /** 根据失败归因给出下一步动作，避免只给分数不给方向。 */
    private String recommendation(boolean pass, List<EvalAnalysis> analyses) {
        if (pass) {
            return "- 评测通过，可将本次结果固化为基线；下一轮对比回归。\n";
        }
        boolean hasRuntime = analyses.stream().anyMatch(a -> "RUNTIME_ERROR".equals(a.cause()));
        boolean hasData = analyses.stream().anyMatch(a -> "DATA_MISSING".equals(a.cause()));
        boolean hasKeyword = analyses.stream().anyMatch(a -> "KEYWORD_TOO_STRICT".equals(a.cause()));
        StringBuilder text = new StringBuilder();
        if (hasRuntime) {
            text.append("- 先修复执行错误，再看检索指标。\n");
        }
        if (hasData) {
            text.append("- 有样本检索为空，优先确认知识库导入和文档版本。\n");
        }
        if (hasKeyword) {
            text.append("- 部分样本只命中部分关键词，检查关键词粒度或分块是否把答案拆散。\n");
        }
        text.append("- 失败样本需要人工抽检，确认是题错、数据错还是检索错后再调整基线。\n");
        return text.toString();
    }

    /** P95 耗时：样本量小时取最大值，避免插值失真。 */
    private long p95(List<RetrievalEvalRunner.EvalResult> results) {
        if (results.isEmpty()) {
            return 0;
        }
        List<Long> costs = results.stream()
                .map(RetrievalEvalRunner.EvalResult::latencyMs)
                .sorted(Comparator.naturalOrder())
                .toList();
        int index = Math.min(costs.size() - 1, (int) Math.ceil(costs.size() * 0.95) - 1);
        return costs.get(Math.max(0, index));
    }

    /** 百分比格式化：统一保留两位小数，报告可直接贴到 PR。 */
    private String percent(double value) {
        return String.format("%.2f%%", value * 100);
    }

    /** Markdown 表格转义：避免 reason/suggestion 里的竖线破坏表格。 */
    private String escape(String text) {
        return text == null ? "" : text.replace("|", "\\|").replace("\n", " ");
    }

    /** 验收产物：通过标记 + Markdown 报告 + 失败归因 + 可供基线对比的统计值。 */
    record Verdict(boolean overallPass, String report, List<EvalAnalysis> analyses, Stats stats) {
    }

    /**
     * 一次评测的汇总指标。
     *
     * 流水线用它写 meta.json 和对比基线；单独抽出来是为了让门禁和落盘使用同一份数字，
     * 避免两处各算一遍导致口径漂移。
     */
    record Stats(int total, int passed, int errors, double passRate, double avgCoverage, long p95LatencyMs) {
    }

    /** 单条失败归因：cause 使用固定枚举，方便后续统计。 */
    record EvalAnalysis(String id, String cause, String reason, String suggestion) {
    }
}
