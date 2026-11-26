package com.example.langchain4j.common.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
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
 * - agent_node_calls_total{node,outcome} —— 图节点调用次数（按节点、成功/失败）；
 * - agent_node_duration_seconds{node} —— 图节点执行耗时；
 * - retrieval_queries_total{outcome} —— 检索轮次（hit/empty）；
 * - retrieval_hits_total{channel} —— 稠密/稀疏/融合命中条数；
 * - retrieval_top_score —— 每轮融合最高分分布；
 * - retrieval_duration_seconds —— 检索整体耗时；
 * - llm_tokens_total{model,kind} —— LLM 输入/输出 token 用量。
 * - memory_extract_total{outcome} —— 长期记忆抽取结果（saved/skipped/failed）；
 * - memory_extract_saved_total —— 实际入库的记忆条数；
 * - memory_merge_total —— 同义合并命中次数；
 * - memory_recall_total —— 召回注入的记忆条数。
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

    /**
     * 记录一次记忆抽取结果。
     *
     * @param outcome saved / skipped / failed；skipped 只在日志里需要细分原因，
     *                指标标签保持低基数，避免 reason 组合爆炸
     */
    public void recordMemoryExtract(String outcome) {
        registry.counter("memory.extract.total",
                        "outcome", safeLabel(outcome),
                        "description", "长期记忆抽取结果")
                .increment();
    }

    /** 实际写入/更新的记忆条数；用于观察抽取产出是否异常。 */
    public void recordMemoryExtractSaved(int count) {
        if (count > 0) {
            registry.counter("memory.extract.saved.total",
                            "description", "长期记忆实际入库条数")
                    .increment(count);
        }
    }

    /** 同义合并命中 +1：说明模型给出的 key 不稳定，但被服务层合并回来了。 */
    public void recordMemoryMerge() {
        registry.counter("memory.merge.total",
                        "description", "长期记忆同义合并命中次数")
                .increment();
    }

    /** 本轮召回注入的记忆条数。 */
    public void recordMemoryRecall(int count) {
        if (count > 0) {
            registry.counter("memory.recall.total",
                            "description", "长期记忆召回注入条数")
                    .increment(count);
        }
    }

    /**
     * 记录一次图节点执行结果与耗时。
     *
     * 节点名来自固定常量集合，标签基数可控；成功/失败表示节点是否抛出异常，
     * 不表达业务结果，业务上的兜底仍由 agent.fallback.total 等指标观察。
     *
     * @param node    图节点名
     * @param success 节点是否正常返回
     * @param costMs  节点执行耗时（毫秒）
     */
    public void recordNodeExecution(String node, boolean success, long costMs) {
        String label = safeLabel(node);
        registry.counter("agent.node.calls.total",
                        "node", label,
                        "outcome", success ? "success" : "failure",
                        "description", "图节点调用次数")
                .increment();
        registry.timer("agent.node.duration",
                        "node", label,
                        "description", "图节点执行耗时")
                .record(costMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录一轮混合检索的召回规模和最高融合分。
     *
     * 这些指标只描述检索质量信号，不参与业务判断；空结果单独计数，
     * 便于区分“确实没有数据”和“检索链路异常降级”。
     *
     * @param denseHits  稠密通道命中数
     * @param sparseHits 稀疏通道命中数
     * @param fusedHits  融合后最终命中数
     * @param topScore   融合最高分
     * @param costMs     检索总耗时（毫秒）
     */
    public void recordRetrieval(int denseHits, int sparseHits, int fusedHits,
                                double topScore, long costMs) {
        registry.counter("retrieval.queries.total",
                        "outcome", fusedHits > 0 ? "hit" : "empty",
                        "description", "检索轮次")
                .increment();
        incrementCounter("retrieval.hits.total", "channel", "dense", denseHits);
        incrementCounter("retrieval.hits.total", "channel", "sparse", sparseHits);
        incrementCounter("retrieval.hits.total", "channel", "fused", fusedHits);
        registry.summary("retrieval.top.score",
                        "description", "每轮融合最高分分布")
                .record(Math.max(0.0, topScore));
        registry.timer("retrieval.duration",
                        "description", "检索整体耗时")
                .record(costMs, TimeUnit.MILLISECONDS);
    }

    /**
     * 记录 LLM 输入/输出 token 用量；供应商未返回 tokenUsage 时静默跳过。
     *
     * token 指标用于成本观察，不参与预算控制；控制逻辑仍在 LlmInvoker 与回答预算中。
     *
     * @param model        模型名
     * @param inputTokens  输入 token 数，可为空
     * @param outputTokens 输出 token 数，可为空
     */
    public void recordLlmTokens(String model, Integer inputTokens, Integer outputTokens) {
        String label = safeLabel(model);
        if (inputTokens != null && inputTokens > 0) {
            registry.counter("llm.tokens.total",
                            "model", label,
                            "kind", "input",
                            "description", "LLM token 用量")
                    .increment(inputTokens);
        }
        if (outputTokens != null && outputTokens > 0) {
            registry.counter("llm.tokens.total",
                            "model", label,
                            "kind", "output",
                            "description", "LLM token 用量")
                    .increment(outputTokens);
        }
    }

    /** 命中数计数器统一入口：非正数不自增，避免无意义时间序列。 */
    private void incrementCounter(String name, String tagKey, String tagValue, int count) {
        if (count > 0) {
            registry.counter(name, tagKey, tagValue).increment(count);
        }
    }

    /** 标签值兜底：空值/异常字符统一替换，防止指标注册异常 */
    private String safeLabel(String value) {
        if (value == null || value.isBlank()) {
            return "unknown";
        }
        return value.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
