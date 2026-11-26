package com.example.langchain4j.eval;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 评测流水线入口：只有显式开启时才连接真实环境。
 *
 * 默认跳过，保证 `mvn verify` 在没有 PG、Redis、模型密钥的机器上仍然能过；
 * 需要评测时用 `-Deval.enabled=true` 或 `EVAL_ENABLED=true` 打开。
 */
class RetrievalEvalPipelineTest {

    /**
     * 串起出题、执行、验收三个阶段，并断言最终门禁结果。
     *
     * 失败时把产物目录带进断言信息，避免 CI 只看到一句断言失败。
     */
    @Test
    void pipelineRunsAllThreeStages() throws Exception {
        assumeTrue(enabled(), "评测流水线未启用：设置 EVAL_ENABLED=true 或 -Deval.enabled=true");

        EvalPipeline.Outcome outcome = new EvalPipeline(EvalConfig.fromSystem()).run();

        assertThat(outcome.passed())
                .as("评测未通过，产物目录: %s", outcome.runDir())
                .isTrue();
    }

    /** 系统属性优先于环境变量；只认 true/1/yes，避免误配成 false 反而开启。 */
    private boolean enabled() {
        String raw = System.getProperty("eval.enabled");
        if (raw == null || raw.isBlank()) {
            raw = System.getenv("EVAL_ENABLED");
        }
        return raw != null
                && ("true".equalsIgnoreCase(raw) || "1".equals(raw) || "yes".equalsIgnoreCase(raw));
    }
}
