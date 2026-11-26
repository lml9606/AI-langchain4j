package com.example.langchain4j.eval;

import java.nio.file.Path;

/**
 * 评测流水线配置：系统属性优先，其次环境变量，最后使用默认值。
 *
 * 这样既能被 Maven -D 参数驱动，也能被 CI/cron 的环境变量驱动，不用改代码。
 */
record EvalConfig(String baseUrl,
                  String token,
                  String username,
                  String password,
                  String suite,
                  double minCoverage,
                  double minPassRate,
                  double minAvgCoverage,
                  Path outputDir,
                  boolean authorEnabled,
                  int authorSamples,
                  boolean reviewEnabled,
                  String llmBaseUrl,
                  String llmApiKey,
                  String llmModel) {

    /** 是否具备调用验收/出题模型的条件；缺 key 时只跑确定性部分。 */
    boolean llmAvailable() {
        return llmApiKey != null && !llmApiKey.isBlank();
    }

    /** 从系统属性/环境变量装配配置；未配置项使用保守默认值。 */
    static EvalConfig fromSystem() {
        return new EvalConfig(
                value("eval.baseUrl", "EVAL_BASE_URL", "http://localhost:8081"),
                value("eval.token", "EVAL_TOKEN", ""),
                value("eval.username", "EVAL_USERNAME", ""),
                value("eval.password", "EVAL_PASSWORD", ""),
                value("eval.suite", "EVAL_SUITE", "full"),
                decimal("eval.minCoverage", "EVAL_MIN_COVERAGE", 0.67),
                decimal("eval.minPassRate", "EVAL_MIN_PASS_RATE", 0.80),
                decimal("eval.minAvgCoverage", "EVAL_MIN_AVG_COVERAGE", 0.70),
                Path.of(value("eval.outputDir", "EVAL_OUTPUT_DIR", "target/eval")),
                bool("eval.authorEnabled", "EVAL_AUTHOR_ENABLED", false),
                integer("eval.authorSamples", "EVAL_AUTHOR_SAMPLES", 5),
                bool("eval.reviewEnabled", "EVAL_REVIEW_ENABLED", true),
                value("eval.llm.baseUrl", "EVAL_LLM_BASE_URL", "https://api.deepseek.com"),
                value("eval.llm.apiKey", "EVAL_LLM_API_KEY", System.getenv().getOrDefault("DEEPSEEK_API_KEY", "")),
                value("eval.llm.model", "EVAL_LLM_MODEL", "deepseek-flash"));
    }

    /** 系统属性 > 环境变量 > 默认值。 */
    private static String value(String property, String env, String fallback) {
        String fromProperty = System.getProperty(property);
        if (fromProperty != null && !fromProperty.isBlank()) {
            return fromProperty.trim();
        }
        String fromEnv = System.getenv(env);
        if (fromEnv != null && !fromEnv.isBlank()) {
            return fromEnv.trim();
        }
        return fallback;
    }

    /** 小数配置解析；非法值回退默认值，避免 CI 因错字直接崩。 */
    private static double decimal(String property, String env, double fallback) {
        try {
            return Double.parseDouble(value(property, env, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 整数配置解析；非法值回退默认值。 */
    private static int integer(String property, String env, int fallback) {
        try {
            return Integer.parseInt(value(property, env, String.valueOf(fallback)));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 布尔配置解析；非法值回退默认值。 */
    private static boolean bool(String property, String env, boolean fallback) {
        String raw = value(property, env, String.valueOf(fallback));
        return "true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw);
    }
}
