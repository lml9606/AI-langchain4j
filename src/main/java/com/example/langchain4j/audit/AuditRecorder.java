package com.example.langchain4j.audit;

/**
 * 审计记录接口：调用方只依赖该接口，具体实现可以是数据库、消息队列、日志或外部审计系统。
 *
 * 审计是旁路可观测性，不是业务事实源。实现必须在内部吞掉或降级自身异常，
 * 不能因为审计失败改变业务返回、事务结果或异常传播。
 */
public interface AuditRecorder {

    /** 记录一条审计；实现必须自行处理自身异常，审计失败不能影响主流程。 */
    void record(AuditEntry entry);
}
