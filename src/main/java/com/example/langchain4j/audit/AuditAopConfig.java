package com.example.langchain4j.audit;

import org.springframework.aop.Advisor;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.aop.support.DefaultPointcutAdvisor;
import org.springframework.aop.support.annotation.AnnotationMatchingPointcut;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 审计注解的 Spring AOP 装配：使用 programmatic advisor，不依赖 AspectJ 织入。
 *
 * <p>只匹配标注了 {@link AuditStep} 的方法；其他 Bean 不会被代理。
 */
@Configuration(proxyBeanMethods = false)
public class AuditAopConfig {

    @Bean
    public Advisor auditStepAdvisor(AuditStepInterceptor interceptor) {
        AnnotationMatchingPointcut pointcut =
                new AnnotationMatchingPointcut(null, AuditStep.class, true);
        return new DefaultPointcutAdvisor(pointcut, interceptor);
    }

    @Bean
    public static DefaultAdvisorAutoProxyCreator auditAdvisorAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
        creator.setProxyTargetClass(false);
        // 只应用审计 advisor，避免把事务等其他 Advisor 一并接管导致重复代理
        creator.setAdvisorBeanNamePrefix("auditStepAdvisor");
        return creator;
    }
}
