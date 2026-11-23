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
        // 强制 CGLIB：被代理的 Bean 可能按具体类型注入，JDK 动态代理会导致
        // "bean is a JDK dynamic proxy" 注入失败。
        creator.setProxyTargetClass(true);
        // 只应用审计 advisor。注意 setAdvisorBeanNamePrefix 只设值，必须配合
        // usePrefix=true 才生效；否则会接管事务等其他 Advisor，给 Bean 套第二层代理。
        creator.setAdvisorBeanNamePrefix("auditStepAdvisor");
        creator.setUsePrefix(true);
        return creator;
    }
}
