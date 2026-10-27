package com.example.langchain4j.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 图执行专用线程池配置。
 * <ul>
 *   <li>有界队列 + AbortPolicy：队列满时拒绝新任务，由调用方兜底（防内存打爆）；</li>
 *   <li>优雅停机：等待在途任务完成后再关闭，配合 server.shutdown=graceful；</li>
 *   <li>与 HTTP 线程池隔离，图执行慢任务不拖垮 Web 层。</li>
 * </ul>
 */
@Configuration
public class AsyncConfig {

    @Bean("agentExecutor")
    public ThreadPoolTaskExecutor agentExecutor(AgentProperties properties) {
        AgentProperties.Executor exec = properties.getExecutor();
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(exec.getCorePoolSize());
        executor.setMaxPoolSize(exec.getMaxPoolSize());
        executor.setQueueCapacity(exec.getQueueCapacity());
        executor.setKeepAliveSeconds(exec.getKeepAliveSeconds());
        executor.setThreadNamePrefix("agent-exec-");
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
        return executor;
    }
}
