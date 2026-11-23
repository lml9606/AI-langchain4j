package com.example.langchain4j.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Map;

/**
 * Agent 业务指标：集中定义与埋点，暴露到 /actuator/prometheus。
 *
 * 指标清单（Prometheus 输出名，点号自动转下划线）：
 *
 * - agent_requests_total —— 对话请求总数；
 * - agent_graph_duration_seconds —— 图执行整体耗时；
 * - agent_fallback_total —— 兜底节点触发次数（异常路径健康度）；
 * - agent_rate_limited_total —— 限流拒绝次数；
 * - llm_calls_total{model,outcome} —— LLM 调用次数（按模型、成功/失败）；
 * - llm_call_duration_seconds{model} —— LLM 单次调用耗时（含重试）；
 * - llm_truncated_total{model} —— LLM 输出被 maxOutputTokens 截断次数（截断感知，不再静默降级）；
 * - llm_circuit_open{model} —— 熔断器状态（1=打开，0=关闭）；
 * - tool_calls_total{tool,outcome} —— 工具调用次数；
 * - tool_call_duration_seconds{tool} —— 工具单次调用耗时。
 *
 * 线程池指标（队列深度/活跃线程）在 AsyncConfig 注册，此处不重复。
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;
    /** 熔断器状态：按模型隔离，Gauge 定期采样上报 */
    private final Map<String, AtomicInteger> circuitOpenByModel = new ConcurrentHashMap<>();

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
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

    /**
     * 记录一次 LLM 输出截断（finishReason=LENGTH）。
     * 截断时结构化 JSON 大概率不完整，调用方已按各自 fail-open 路径降级，此处打点消除静默不可感知。
     *
     * @param model 模型名（标签值）
     */
    public void recordLlmTruncated(String model) {
        registry.counter("llm.truncated.total",
                        "model", safeLabel(model),
                        "description", "LLM 输出被 maxOutputTokens 截断的次数")
                .increment();
    }

    /**
     * 记录一次工具调用。
     *
     * @param tool    工具名（标签值，防高基数）
     * @param success 是否成功
     * @param costMs  耗时（毫秒）
     */
    public void recordToolCall(String tool, boolean success, long costMs) {
        registry.counter("tool.calls.total",
                        "tool", safeLabel(tool),
                        "outcome", success ? "success" : "failure")
                .increment();
        registry.timer("tool.call.duration",
                        "tool", safeLabel(tool))
                .record(costMs, TimeUnit.MILLISECONDS);
    }

    /** 更新指定模型熔断器状态（打开=1 / 关闭=0），模型首次出现时注册 Gauge。 */
    public void setCircuitOpen(String model, boolean open) {
        String label = safeLabel(model);
        AtomicInteger state = circuitOpenByModel.computeIfAbsent(label, key -> {
            AtomicInteger value = new AtomicInteger(0);
            Gauge.builder("llm.circuit.open", value, AtomicInteger::doubleValue)
                    .tag("model", key)
                    .description("LLM 熔断器状态：1=打开（快速失败），0=关闭")
                    .register(registry);
            return value;
        });
        state.set(open ? 1 : 0);
    }

    /** 标签值兜底：空值/异常字符统一替换，防止指标注册异常 */
    private String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
