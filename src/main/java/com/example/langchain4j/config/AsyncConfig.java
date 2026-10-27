package com.example.langchain4j.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 图执行专用线程池配置。
 * <ul>
 *   <li>有界队列 + AbortPolicy：队列满时拒绝新任务，由调用方兜底（防内存打爆）；</li>
 *   <li>优雅停机：等待在途任务完成后再关闭，配合 server.shutdown=graceful；</li>
 *   <li>与 HTTP 线程池隔离，图执行慢任务不拖垮 Web 层；</li>
 *   <li>MDC 传递：TaskDecorator 把 traceId 复制到工作线程，图内日志可串链；</li>
 *   <li>容量指标：注册队列深度/活跃线程 Gauge，供告警规则监测线程池饱和。</li>
 * </ul>
 */
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
        // MDC 上下文传递：HTTP 线程的 traceId 随任务带入工作线程，图内日志不丢链路标识
        executor.setTaskDecorator(runnable -> {
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
        });
        // 拒绝策略：队列满抛异常 → LlmGateway/service 捕获转业务错误码，而不是无限等待
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy() {
            @Override
            public void rejectedExecution(Runnable r, ThreadPoolExecutor e) {
                throw new RejectedExecutionException("agent executor overloaded, queue full");
            }
        });
        // 优雅停机：等待在途任务收尾
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        registerExecutorMetrics(executor, registry);
        return executor;
    }

    /** 注册线程池容量指标（initialize 之后 ThreadPoolExecutor 才可用） */
    private void registerExecutorMetrics(ThreadPoolTaskExecutor executor, MeterRegistry registry) {
        Gauge.builder("agent.executor.queue.depth", executor,
                        e -> e.getThreadPoolExecutor().getQueue().size())
                .description("图执行线程池队列中等待的任务数")
                .register(registry);
        Gauge.builder("agent.executor.active.threads", executor,
                        e -> e.getThreadPoolExecutor().getActiveCount())
                .description("图执行线程池活跃线程数")
                .register(registry);
        Gauge.builder("agent.executor.pool.size", executor,
                        e -> e.getThreadPoolExecutor().getPoolSize())
                .description("图执行线程池当前线程数")
                .register(registry);
        // 队列容量是静态配置值，注册为常量 Gauge 供告警规则计算饱和度
        Gauge.builder("agent.executor.queue.capacity", executor,
                        e -> e.getThreadPoolExecutor().getQueue().remainingCapacity()
                                + e.getThreadPoolExecutor().getQueue().size())
                .description("图执行线程池队列容量")
                .register(registry);
    }
}
