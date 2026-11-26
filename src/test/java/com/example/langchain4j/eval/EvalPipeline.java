package com.example.langchain4j.eval;

import com.example.langchain4j.common.util.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 评测流水线编排：出题 -> 执行 -> 验收 -> 落盘 -> 基线对比。
 *
 * 三个阶段通过文件交接，而不是依赖人工开三个对话窗口：author.md 说明出题阶段做了什么，
 * raw.jsonl 保存每条样本的原始执行结果，verdict.md 保存验收结论。
 * 运行结束后只看输出目录即可复盘，CI 只需要读取退出码。
 */
public final class EvalPipeline {

    private static final Logger log = LoggerFactory.getLogger(EvalPipeline.class);
    private static final String GOLDEN_RESOURCE = "eval/golden-retrieval.jsonl";
    private static final String BASELINE_FILE = "src/test/resources/eval/baseline.json";
    private static final String BASELINE_RESOURCE = "eval/baseline.json";
    /** 允许的指标回退幅度：超过 5 个百分点视为回归，避免正常波动天天告警。 */
    private static final double MAX_REGRESSION = 0.05;
    private static final DateTimeFormatter RUN_ID_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final EvalConfig config;

    /** 注入配置；构造阶段不访问网络和磁盘，方便单测和复用。 */
    EvalPipeline(EvalConfig config) {
        this.config = config;
    }

    /**
     * 命令行入口：跑完整流水线，按门禁结果返回退出码。
     *
     * 退出码约定：0=通过，1=门禁或基线对比失败，2=流水线自身异常（环境不可用、配置缺失）。
     */
    public static void main(String[] args) {
        int exitCode;
        try {
            Outcome outcome = new EvalPipeline(EvalConfig.fromSystem()).run();
            exitCode = outcome.passed() ? 0 : 1;
        } catch (Exception e) {
            log.error("[eval-pipeline] 执行失败: {}", e.getMessage(), e);
            System.err.println("[eval-pipeline] 执行失败: " + e.getMessage());
            exitCode = 2;
        }
        System.exit(exitCode);
    }

    /**
     * 顺序执行三个阶段并把产物落到本次运行目录。
     *
     * 阶段之间只通过内存对象和文件交接：出题决定本次要跑的样本，执行只负责取回原始结果，
     * 验收只负责判定和归因，职责不互相污染。
     *
     * @return 是否通过、产物目录、可读摘要
     * @throws IOException 产物写入失败时抛出；这类失败通常意味着磁盘或权限问题，不应吞掉
     */
    Outcome run() throws IOException {
        LocalDateTime startedAt = LocalDateTime.now();
        Path runDir = config.outputDir().resolve(startedAt.format(RUN_ID_FORMAT));
        Files.createDirectories(runDir);
        log.info("[eval-pipeline] start suite={}, baseUrl={}, output={}",
                config.suite(), config.baseUrl(), runDir);

        // 阶段一：加载并校验测评集，smoke 只取前 10 条，避免每次 PR 都跑全量
        List<EvalCase> golden = EvalCase.selectSuite(
                EvalCase.loadClasspath(GOLDEN_RESOURCE), config.suite());
        EvalCase.validate(golden);
        EvalLlmClient llmClient = new EvalLlmClient(config);
        EvalAuthorAgent.AuthorOutcome authored = new EvalAuthorAgent(config, llmClient).prepare(golden);
        Files.writeString(runDir.resolve("author.md"), renderAuthor(authored, golden), StandardCharsets.UTF_8);

        // 阶段二：先探活再执行，服务没起来时直接失败，不制造一堆误导性的 RUNTIME_ERROR
        EvalHttpClient httpClient = new EvalHttpClient(config);
        httpClient.healthCheck();
        List<RetrievalEvalRunner.EvalResult> results =
                new RetrievalEvalRunner(httpClient, config.minCoverage()).run(authored.cases());
        writeJsonl(runDir.resolve("raw.jsonl"), results);

        // 阶段三：硬门禁由代码计算，LLM 只做失败归因
        EvalReviewerAgent.Verdict verdict = new EvalReviewerAgent(config, llmClient).review(results);
        Files.writeString(runDir.resolve("verdict.md"), verdict.report(), StandardCharsets.UTF_8);

        // 基线对比：无基线时只产出候选文件，绝不自动覆盖已提交的基线
        Baseline baseline = readBaseline();
        Regression regression = compareBaseline(baseline, verdict.stats());
        boolean passed = verdict.overallPass() && !regression.regressed();

        writeMeta(runDir.resolve("meta.json"), startedAt, verdict.stats(), baseline, regression, passed);
        String summary = renderSummary(runDir, verdict, baseline, regression, passed);
        Files.writeString(runDir.resolve("summary.md"), summary, StandardCharsets.UTF_8);
        if (passed && baseline == null) {
            writeBaselineCandidate(runDir.resolve("baseline-candidate.json"), verdict.stats());
        }

        System.out.println(verdict.report());
        System.out.println(summary);
        log.info("[eval-pipeline] finished passed={}, passRate={}, avgCoverage={}, dir={}",
                passed, percent(verdict.stats().passRate()),
                percent(verdict.stats().avgCoverage()), runDir);
        return new Outcome(passed, runDir, summary);
    }

    /** 出题阶段产物：报告前面补上套件和样本数，便于和 meta.json 对照。 */
    private String renderAuthor(EvalAuthorAgent.AuthorOutcome authored, List<EvalCase> golden) {
        return "# 出题阶段\n\n"
                + "- 套件: " + config.suite() + "\n"
                + "- 基础样本: " + golden.size() + "\n"
                + "- 执行样本: " + authored.cases().size() + "\n\n"
                + authored.report();
    }

    /** 原始结果按 JSONL 落盘，一行一条，方便 grep 和后续用脚本二次统计。 */
    private void writeJsonl(Path path, List<RetrievalEvalRunner.EvalResult> results) throws IOException {
        StringBuilder text = new StringBuilder();
        for (RetrievalEvalRunner.EvalResult result : results) {
            text.append(JsonUtil.toJson(result)).append('\n');
        }
        Files.writeString(path, text.toString(), StandardCharsets.UTF_8);
    }

    /**
     * 读取基线。
     *
     * 优先读源码目录下的文件（方便本地改完立即生效），没有再读 classpath 里的已提交版本。
     * 文件损坏时只告警并跳过对比，不能让基线格式问题把整次评测打挂。
     */
    private Baseline readBaseline() {
        Path file = Path.of(BASELINE_FILE);
        try {
            if (Files.exists(file)) {
                return JsonUtil.fromJson(Files.readString(file, StandardCharsets.UTF_8), Baseline.class);
            }
            try (InputStream input = EvalPipeline.class.getClassLoader()
                    .getResourceAsStream(BASELINE_RESOURCE)) {
                if (input == null) {
                    return null;
                }
                return JsonUtil.mapper().readValue(input, Baseline.class);
            }
        } catch (Exception e) {
            log.warn("[eval-pipeline] 基线读取失败，跳过对比: {}", e.getMessage());
            return null;
        }
    }

    /**
     * 对比基线：通过率或平均覆盖率回退超过 5 个百分点即判回归。
     *
     * 套件不同、样本量缺失时跳过对比，避免拿 smoke 的成绩去要求 full，造成假回归。
     */
    private Regression compareBaseline(Baseline baseline, EvalReviewerAgent.Stats stats) {
        if (baseline == null) {
            return new Regression(false, "无基线，首次运行只生成候选基线");
        }
        if (baseline.caseCount() <= 0) {
            return new Regression(false, "基线缺少样本数，跳过对比");
        }
        if (baseline.suite() != null && !baseline.suite().isBlank()
                && !baseline.suite().equalsIgnoreCase(config.suite())) {
            return new Regression(false, "基线套件=" + baseline.suite()
                    + "，本次套件=" + config.suite() + "，跳过对比");
        }
        double passDrop = baseline.passRate() - stats.passRate();
        double coverageDrop = baseline.avgCoverage() - stats.avgCoverage();
        boolean regressed = passDrop > MAX_REGRESSION || coverageDrop > MAX_REGRESSION;
        String detail = "基线通过率 " + percent(baseline.passRate())
                + "，本次 " + percent(stats.passRate())
                + "；基线平均覆盖率 " + percent(baseline.avgCoverage())
                + "，本次 " + percent(stats.avgCoverage())
                + "；允许回退 " + percent(MAX_REGRESSION);
        return new Regression(regressed, detail);
    }

    /** 写入本次运行的元信息：环境、模型、阈值、基线结论，便于事后定位“这次为什么失败”。 */
    private void writeMeta(Path path, LocalDateTime startedAt, EvalReviewerAgent.Stats stats,
                           Baseline baseline, Regression regression, boolean passed) throws IOException {
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("timestamp", startedAt.toString());
        meta.put("suite", config.suite());
        meta.put("caseCount", stats.total());
        meta.put("baseUrl", config.baseUrl());
        meta.put("model", config.llmAvailable() ? config.llmModel() : "rule-only");
        meta.put("commit", gitCommit());
        meta.put("passed", passed);
        meta.put("stats", stats);
        meta.put("baseline", baseline);
        meta.put("regression", regression);
        meta.put("thresholds", Map.of(
                "minCoverage", config.minCoverage(),
                "minPassRate", config.minPassRate(),
                "minAvgCoverage", config.minAvgCoverage(),
                "maxRegression", MAX_REGRESSION));
        String json = JsonUtil.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(meta);
        Files.writeString(path, json, StandardCharsets.UTF_8);
    }

    /** 生成候选基线；只写进本次运行目录，是否提升为正式基线由人评审后决定。 */
    private void writeBaselineCandidate(Path path, EvalReviewerAgent.Stats stats) throws IOException {
        Baseline candidate = new Baseline(stats.passRate(), stats.avgCoverage(), stats.total(),
                config.suite(), LocalDateTime.now().toString(), gitCommit());
        String json = JsonUtil.mapper().writerWithDefaultPrettyPrinter().writeValueAsString(candidate);
        Files.writeString(path, json, StandardCharsets.UTF_8);
        log.info("[eval-pipeline] 已生成候选基线 {}，确认后复制到 {}",
                path, BASELINE_FILE);
    }

    /** 摘要控制在十几行，CI 日志里一眼能看完；详细内容仍在 verdict.md。 */
    private String renderSummary(Path runDir, EvalReviewerAgent.Verdict verdict,
                                 Baseline baseline, Regression regression, boolean passed) {
        EvalReviewerAgent.Stats stats = verdict.stats();
        StringBuilder text = new StringBuilder();
        text.append("# 评测摘要\n\n");
        text.append("- 结论: ").append(passed ? "PASS" : "FAIL").append('\n');
        text.append("- 通过率: ").append(percent(stats.passRate()))
                .append("（阈值 ").append(percent(config.minPassRate())).append("）\n");
        text.append("- 平均覆盖率: ").append(percent(stats.avgCoverage()))
                .append("（阈值 ").append(percent(config.minAvgCoverage())).append("）\n");
        text.append("- 执行错误: ").append(stats.errors()).append('\n');
        text.append("- P95 耗时: ").append(stats.p95LatencyMs()).append("ms\n");
        text.append("- 基线: ").append(baselineCommit(baseline))
                .append("；").append(regression.detail()).append('\n');
        text.append("- 输出目录: ").append(runDir).append('\n');
        text.append("\n## 产物\n\n");
        text.append("- author.md：出题阶段报告\n");
        text.append("- raw.jsonl：逐条原始执行结果\n");
        text.append("- verdict.md：验收结论、门禁表和失败归因\n");
        text.append("- meta.json：环境、阈值、基线与提交信息\n");
        text.append("- baseline-candidate.json：无基线且通过时生成，需人工确认后再提升\n");
        return text.toString();
    }

    /**
     * 取当前提交号；git 不可用（例如打包后的环境）时返回 unknown，不影响评测本身。
     *
     * stderr 直接丢弃而不是合并进 stdout：CI 或沙箱里 git 可能报 safe.directory 之类的错误，
     * 合并会把报错当成提交号写进 meta.json。只有退出码为 0 且形如 SHA 的输出才算有效。
     */
    private String gitCommit() {
        try {
            Process process = new ProcessBuilder("git", "-c",
                    "safe.directory=" + Path.of("").toAbsolutePath(),
                    "rev-parse", "HEAD")
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return "unknown";
            }
            if (process.exitValue() != 0) {
                return "unknown";
            }
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            return output.matches("[0-9a-f]{7,40}") ? output : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /** 基线提交号可能缺失（手工编写的基线），统一降级成可读文案而不是打印 null。 */
    private String baselineCommit(Baseline baseline) {
        if (baseline == null) {
            return "无";
        }
        return baseline.commit() == null || baseline.commit().isBlank()
                ? "已有基线" : baseline.commit();
    }

    /** 百分比统一保留两位小数，报告和日志口径一致。 */
    private String percent(double value) {
        return String.format("%.2f%%", value * 100);
    }

    /** 流水线执行结果：通过标记、产物目录、摘要。 */
    record Outcome(boolean passed, Path runDir, String summary) {
    }

    /** 已提交的基线：只保留门禁需要的最小字段，避免基线文件膨胀。 */
    record Baseline(double passRate, double avgCoverage, int caseCount,
                    String suite, String createdAt, String commit) {
    }

    /** 基线对比结论：regressed 为 false 时 detail 仍然要展示，说明为什么没参与对比。 */
    record Regression(boolean regressed, String detail) {
    }
}
