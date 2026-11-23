package com.example.langchain4j.audit;

import com.example.langchain4j.service.AuditService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

import java.util.concurrent.RejectedExecutionException;

/**
 * 异步审计记录器：业务线程只提交任务，数据库写入在隔离线程池执行。
 *
 * 审计不是业务事实源，队列满时丢弃并告警，保证对话主链路不被审计拖慢。
 * executionId（traceId）在创建 AuditEntry 时已经固定，异步后仍可串联同一次请求的审计链路。
 */
@Slf4j
@Component("asyncAuditRecorder")
public class AsyncAuditRecorder implements AuditRecorder {

    private final AuditService auditService;
    private final ThreadPoolTaskExecutor executor;

    public AsyncAuditRecorder(AuditService auditService,
                              @Qualifier("auditExecutor") ThreadPoolTaskExecutor executor) {
        this.auditService = auditService;
        this.executor = executor;
    }

    /** 提交一条异步写入；线程池拒绝时丢弃审计并告警，不阻塞业务。 */
    @Override
    public void record(AuditEntry entry) {
        try {
            executor.execute(() -> auditService.record(entry));
        } catch (RejectedExecutionException e) {
            log.warn("[audit] executor rejected, drop audit record, node={}", entry.nodeName());
        }
    }
}
