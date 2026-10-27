package com.example.langchain4j.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent 业务指标：集中定义与埋点，暴露到 /actuator/prometheus。
 * <p>
 * 指标清单（Prometheus 输出名，点号自动转下划线）：
 * <ul>
 *   <li>agent_requests_total —— 对话请求总数；</li>
 *   <li>agent_graph_duration_seconds —— 图执行整体耗时；</li>
 *   <li>agent_fallback_total —— 兜底节点触发次数（异常路径健康度）；</li>
 *   <li>agent_rate_limited_total —— 限流拒绝次数；</li>
 *   <li>llm_calls_total{model,outcome} —— LLM 调用次数（按模型、成功/失败）；</li>
 *   <li>llm_call_duration_seconds{model} —— LLM 单次调用耗时（含重试）；</li>
 *   <li>llm_circuit_open —— 熔断器状态（1=打开，0=关闭）。</li>
 * </ul>
 * 线程池指标（队列深度/活跃线程）在 AsyncConfig 注册，此处不重复。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    /** 熔断器状态：跨线程共享，Gauge 定期采样上报 */
    private final AtomicInteger circuitOpen = new AtomicInteger(0);

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
        // Gauge 构造即注册；后续只需更新 circuitOpen 的值
        Gauge.builder("llm.circuit.open", circuitOpen, AtomicInteger::doubleValue)
                .description("LLM 熔断器状态：1=打开（快速失败），0=关闭")
                .register(registry);
    }

    /** 对话请求 +1 */
    public void recordAgentRequest() {
        registry.counter("agent.requests.total",
                "description", "Agent 对话请求总数").increment();
    }

    /** 图执行完成耗时（毫秒） */
    public void recordGraphDuration(long costMs) {
        registry.timer("agent.graph.duration",
                        "description", "图执行整体耗时")
                .record(costMs, TimeUnit.MILLISECONDS);
    }

    /** 兜底节点触发 +1 */
    public void recordFallback() {
        registry.counter("agent.fallback.total",
                "description", "兜底节点触发次数").increment();
    }

    /** 限流拒绝 +1 */
    public void recordRateLimited() {
        registry.counter("agent.rate.limited.total",
                "description", "限流拒绝次数").increment();
    }

    /**
     * 记录一次 LLM 调用（重试耗尽 / 熔断快速失败计 1 次失败）。
     *
     * @param model    模型名（标签值，防止高基数导致指标爆炸）
     * @param success  是否成功
     * @param costMs   本次调用总耗时（含重试退避等待）
     */
    public void recordLlmCall(String model, boolean success, long costMs) {
        registry.counter("llm.calls.total",
                        "model", safeLabel(model),
                        "outcome", success ? "success" : "failure")
                .increment();
        registry.timer("llm.call.duration",
                        "model", safeLabel(model))
                .record(costMs, TimeUnit.MILLISECONDS);
    }

    /** 更新熔断器状态（打开=1 / 关闭=0） */
    public void setCircuitOpen(boolean open) {
        circuitOpen.set(open ? 1 : 0);
    }

    /** 标签值兜底：空值/异常字符统一替换，防止指标注册异常 */
    private String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
