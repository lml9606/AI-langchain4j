package com.example.langchain4j.common.metrics;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 指标埋点单测：验证标签、计数、耗时和 token 用量按预期落到 Micrometer。 */
class AgentMetricsTest {

    /** 节点成功执行应同时产生成功计数与耗时计时器。 */
    @Test
    void recordsSuccessfulNodeExecution() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordNodeExecution("generate", true, 120);

        assertThat(registry.get("agent.node.calls.total")
                .tags("node", "generate", "outcome", "success")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.get("agent.node.duration")
                .tags("node", "generate")
                .timer().totalTime(TimeUnit.MILLISECONDS)).isCloseTo(120.0, within(1.0));
    }

    /** 节点抛异常时应落到 failure 标签，不能混入成功计数。 */
    @Test
    void recordsFailedNodeExecutionWithFailureTag() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordNodeExecution("validate", false, 25);

        assertThat(registry.get("agent.node.calls.total")
                .tags("node", "validate", "outcome", "failure")
                .counter().count()).isEqualTo(1.0);
        assertThat(registry.find("agent.node.calls.total")
                .tag("node", "validate")
                .tag("outcome", "success")
                .counter()).isNull();
    }

    /** 每轮检索应记录 hit/empty 结果、三通道命中数、最高分和耗时。 */
    @Test
    void recordsRetrievalHitAndEmptyOutcomes() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordRetrieval(3, 2, 4, 0.75, 40);
        metrics.recordRetrieval(0, 0, 0, -1.0, 10);

        assertThat(registry.get("retrieval.queries.total").tags("outcome", "hit").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("retrieval.queries.total").tags("outcome", "empty").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("retrieval.hits.total").tags("channel", "dense").counter().count())
                .isEqualTo(3.0);
        assertThat(registry.get("retrieval.hits.total").tags("channel", "sparse").counter().count())
                .isEqualTo(2.0);
        assertThat(registry.get("retrieval.hits.total").tags("channel", "fused").counter().count())
                .isEqualTo(4.0);
        assertThat(registry.get("retrieval.top.score").summary().count()).isEqualTo(2);
        // 负分被归一到 0，不能污染最高分分布
        assertThat(registry.get("retrieval.top.score").summary().totalAmount()).isEqualTo(0.75);
    }

    /** token 指标按 input/output 分开累计，模型名中的特殊字符应被安全替换。 */
    @Test
    void recordsInputAndOutputTokensWithSafeModelLabel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.recordLlmTokens("qwen-max/2025", 12, 30);

        assertThat(registry.get("llm.tokens.total")
                .tags("model", "qwen-max_2025", "kind", "input")
                .counter().count()).isEqualTo(12.0);
        assertThat(registry.get("llm.tokens.total")
                .tags("model", "qwen-max_2025", "kind", "output")
                .counter().count()).isEqualTo(30.0);
    }
}
