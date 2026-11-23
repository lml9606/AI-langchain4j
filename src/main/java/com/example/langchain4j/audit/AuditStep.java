package com.example.langchain4j.audit;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 公开业务步骤审计注解：标注在 Spring Bean 的 public 方法上，由切面统一记录
 * 耗时、入参摘要、返回值摘要和异常。会话 ID 自动从参数名
 * conversationId/threadId 或 WorkflowState 中读取。
 *
 * 注意：Spring AOP 只拦截经过代理的 public 方法调用；同类内部 this 调用、
 * private 方法不会触发。需要结构化业务字段时，让返回值实现
 * {@link AuditPayloadProvider}，不要依赖 toString。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface AuditStep {

    /** 审计节点名，对应 ai_graph_execution.node_name。 */
    String value();
}
