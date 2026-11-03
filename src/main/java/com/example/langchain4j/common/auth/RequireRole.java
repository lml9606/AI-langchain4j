package com.example.langchain4j.common.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 角色鉴权注解：标注在 Controller 类或方法上，由 {@link RoleInterceptor} 校验。
 * <p>
 * 解析优先级：方法级注解覆盖类级注解（允许类整体收口、个别方法放开）。
 * 未标注的接口对所有已认证用户开放（保持默认拒绝原则下逐点收紧）。
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {

    /** 访问该接口所需的最小角色 */
    UserRole value();
}
