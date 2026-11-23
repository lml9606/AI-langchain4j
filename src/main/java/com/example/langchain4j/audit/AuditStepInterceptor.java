package com.example.langchain4j.audit;

import com.example.langchain4j.common.util.Throwables;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.repository.entity.GraphExecution;
import lombok.extern.slf4j.Slf4j;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;

/**
 * {@link AuditStep} 方法拦截器：公开业务步骤的统一审计入口。
 *
 * 审计失败只告警，不影响业务；方法异常在记录后原样抛出。
 */
@Slf4j
@Component
public class AuditStepInterceptor implements MethodInterceptor {

    private final AuditRecorder auditRecorder;
    private final AgentProperties properties;

    public AuditStepInterceptor(AuditRecorder auditRecorder,
                                AgentProperties properties) {
        this.auditRecorder = auditRecorder;
        this.properties = properties;
    }

    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
        Method targetMethod = targetMethod(invocation);
        AuditStep auditStep = targetMethod.getAnnotation(AuditStep.class);
        if (auditStep == null) {
            return invocation.proceed();
        }
        long start = System.currentTimeMillis();
        Object[] args = invocation.getArguments();
        String[] names = parameterNames(targetMethod);
        String conversationId = resolveConversationId(names, args);
        int maxLength = Math.max(1, properties.getLimits().getAuditNodeValueMaxLength());
        Map<String, Object> input = AuditPayloads.methodInput(names, args, maxLength);
        try {
            Object result = invocation.proceed();
            Map<String, Object> output = AuditPayloads.result(result, maxLength);
            safeRecord(AuditEntry.of(conversationId, auditStep.value(),
                    GraphExecution.STATUS_SUCCESS, input, output, null,
                    System.currentTimeMillis() - start, null));
            return result;
        } catch (Throwable error) {
            safeRecord(AuditEntry.of(conversationId, auditStep.value(),
                    GraphExecution.STATUS_FAILED, input, Map.of(),
                    Throwables.rootMessage(error), System.currentTimeMillis() - start, null));
            throw error;
        }
    }

    /** JDK 代理拿到的是接口方法，注解在实现类上，需要解析到最具体方法。 */
    private Method targetMethod(MethodInvocation invocation) {
        return AopUtils.getMostSpecificMethod(invocation.getMethod(),
                AopUtils.getTargetClass(invocation.getThis()));
    }

    /** 参数名依赖编译期 -parameters；缺失时退化为 arg0/arg1。 */
    private String[] parameterNames(Method method) {
        Parameter[] parameters = method.getParameters();
        String[] names = new String[parameters.length];
        for (int index = 0; index < parameters.length; index++) {
            names[index] = parameters[index].isNamePresent()
                    ? parameters[index].getName() : "arg" + index;
        }
        return names;
    }

    /** 会话 ID 解析顺序：同名参数 → WorkflowState。 */
    private String resolveConversationId(String[] names, Object[] args) {
        if (names != null && args != null) {
            for (int index = 0; index < names.length && index < args.length; index++) {
                String name = names[index];
                if (("conversationId".equals(name) || "threadId".equals(name))
                        && args[index] != null) {
                    return String.valueOf(args[index]);
                }
            }
        }
        if (args != null) {
            for (Object arg : args) {
                if (arg instanceof WorkflowState state) {
                    return state.conversationId();
                }
            }
        }
        return "";
    }

    /** 审计实现异常不能反向破坏业务方法。 */
    private void safeRecord(AuditEntry entry) {
        try {
            auditRecorder.record(entry);
        } catch (Exception e) {
            log.warn("[audit] method audit failed, node={}, reason={}", entry.nodeName(),
                    e.getMessage());
        }
    }
}
