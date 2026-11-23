package com.example.langchain4j.audit;

/**
 * 审计记录接口：业务只依赖该接口，默认实现写数据库，后续可替换为异步队列、
 * 日志或外部审计系统。
 */
public interface AuditRecorder {

    /** 记录一条审计；实现必须自行吞掉异常，审计失败不能影响主流程。 */
    void record(AuditEntry entry);
}
