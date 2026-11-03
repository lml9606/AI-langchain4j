package com.example.langchain4j.common.auth;

/**
 * 最小角色模型：USER（普通用户）/ ADMIN（管理员）。
 * <p>
 * 角色来源：注册默认 USER，管理员由运维 SQL 指定（不提供注册提权通道）；
 * 角色随登录签入 JWT claim，随 {@link UserContext} 在请求链路内传递。
 */
public enum UserRole {

    /** 普通用户：仅可操作本人资源 */
    USER,

    /** 管理员：可访问 @RequireRole(ADMIN) 标记的管理类接口 */
    ADMIN
}
