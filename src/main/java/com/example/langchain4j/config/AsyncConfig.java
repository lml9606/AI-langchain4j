package com.example.langchain4j.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 线程池配置：图执行（agentExecutor）、LLM 调用（llmExecutor）、摘要（summaryExecutor）三个隔离池。
 * <p>
 * 隔离原因：
 * <ul>
 *   <li>agentExecutor 与 HTTP 线程池隔离，图执行慢任务不拖垮 Web 层；</li>
 *   <li>llmExecutor 与 agentExecutor 隔离：图线程会同步等待 LLM 结果（future.get 超时控制），
 *       共用一池时高并发下两者相互等待形成准死锁（详见 llmExecutor Bean 注释）；</li>
 *   <li>summaryExecutor 单线程：摘要任务不阻塞主链路、可丢弃，下轮装配按幂等锚点续跑。</li>
 * </ul>
 * 共性：有界队列 + 拒绝策略（防内存打爆）、优雅停机（配合 server.shutdown=graceful）、
 * MDC 传递（TaskDecorator 复制 traceId 到工作线程）、容量 Gauge（供告警规则监测线程池饱和）。
 */
@Slf4j
@Configuration
public class AsyncConfig {

    @Bean("agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor(AgentProperties properties, MeterRegistry registry) {
        AgentProperties.Executor exec = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(exec.getCorePoolSize());
        executor.setMaxPoolSize(exec.getMaxPoolSize());
        executor.setQueueCapacity(exec.getQueueCapacity());
        executor.setKeepAliveSeconds(exec.getKeepAliveSeconds());
        executor.setThreadNamePrefix("agent-exec-");
        executor.setTaskDecorator(mdcTaskDecorator());
        // 拒绝策略：队列满抛异常 → service 捕获转业务错误码，而不是无限等待
        executor.setRejectedExecutionHandler(abortPolicy("agent executor overloaded, queue full"));
        // 优雅停机：等待在途任务收尾
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        registerExecutorMetrics("agent", executor, registry);
        return executor;
    }

    /**
     * LLM 调用专用线程池：与图执行线程池隔离。
     * <p>
     * 隔离原因：图执行线程会同步等待 LLM 结果（LlmGateway 的 future.get 超时控制），
     * 若 LLM 任务与图执行共用同一池，高并发下图线程占满线程池、LLM 任务在队列排队，
     * 两者相互等待形成准死锁，只能靠超时兜底；独立池后图线程等待不影响 LLM 任务执行。
     * 合理并发数 ≈ LLM 配额(QPS) × 平均响应秒数（利特尔法则），按实际配额调优。
     */
    @Bean("llmExecutor")
    public ThreadPoolTaskExecutor llmExecutor(AgentProperties properties, MeterRegistry registry) {
        AgentProperties.LlmExecutor exec = properties.getLlmExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(exec.getCorePoolSize());
        executor.setMaxPoolSize(exec.getMaxPoolSize());
        executor.setQueueCapacity(exec.getQueueCapacity());
        executor.setKeepAliveSeconds(exec.getKeepAliveSeconds());
        executor.setThreadNamePrefix("llm-exec-");
        executor.setTaskDecorator(mdcTaskDecorator());
        // 拒绝策略：队列满抛异常 → LlmGateway 重试/熔断兜底
        executor.setRejectedExecutionHandler(abortPolicy("llm executor overloaded, queue full"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        registerExecutorMetrics("llm", executor, registry);
        return executor;
    }

    /** MDC 上下文传递装饰器：HTTP 线程的 traceId 随任务带入工作线程，异步日志不丢链路标识 */
    private TaskDecorator mdcTaskDecorator() {
        return runnable -> {
            Map<String, String> context = MDC.getCopyOfContextMap();
            return () -> {
                if (context != null) {
                    MDC.setContextMap(context);
                }
                try {
                    runnable.run();
                } finally {
                    MDC.clear();
                }
            };
        };
    }

    /** 拒绝策略：队列满抛异常（带自定义信息），由调用方捕获转业务错误码，而不是无限等待 */
    private RejectedExecutionHandler abortPolicy(String message) {
        return new ThreadPoolExecutor.AbortPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                throw new RejectedExecutionException(message);
            }
        };
    }

    /**
     * 摘要线程池：段摘要异步生成专用，与图执行线程池隔离。
     * <p>
     * 摘要任务不阻塞主链路、可丢弃（失败/满队列时下轮装配会重新触发，靠 covered_message_id 幂等续跑），
     * 因此单线程 + 有界队列即可，拒绝策略为静默丢弃。
     */
    @Bean("summaryExecutor")
    public ThreadPoolTaskExecutor summaryExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(1);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("summary-exec-");
        executor.setRejectedExecutionHandler((r, e) ->
                log.warn("[summary] executor overloaded, drop summary task, retry next round"));
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    /** 注册线程池容量指标（initialize 之后 ThreadPoolExecutor 才可用）；prefix 区分不同线程池的指标名 */
    private void registerExecutorMetrics(String prefix, ThreadPoolTaskExecutor executor, MeterRegistry registry) {
        Gauge.builder(prefix + ".executor.queue.depth", executor,
                        e -> e.getThreadPoolExecutor().getQueue().size())
                .description("线程池队列中等待的任务数")
                .register(registry);
        Gauge.builder(prefix + ".executor.active.threads", executor,
                        e -> e.getThreadPoolExecutor().getActiveCount())
                .description("线程池活跃线程数")
                .register(registry);
        Gauge.builder(prefix + ".executor.pool.size", executor,
                        e -> e.getThreadPoolExecutor().getPoolSize())
                .description("线程池当前线程数")
                .register(registry);
        // 队列容量是静态配置值，注册为常量 Gauge 供告警规则计算饱和度
        Gauge.builder(prefix + ".executor.queue.capacity", executor,
                        e -> e.getThreadPoolExecutor().getQueue().remainingCapacity()
                                + e.getThreadPoolExecutor().getQueue().size())
                .description("线程池队列容量")
                .register(registry);
    }
}
