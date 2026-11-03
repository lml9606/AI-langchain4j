package com.example.langchain4j.common.auth;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;

/**
 * 当前登录用户上下文（ThreadLocal）。
 * <p>
 * 生命周期：
 * <ul>
 *   <li>HTTP 线程：{@code JwtAuthFilter} 解析 Token 后写入，请求结束清理；</li>
 *   <li>异步线程：{@code AsyncConfig} 的 TaskDecorator 随任务透传（图执行线程池内的检索
 *       按当前用户做知识库隔离）；</li>
 *   <li>无上下文场景（定时任务等）：{@link #get()} 返回 null，调用方按“匿名仅见公共数据”降级。</li>
 * </ul>
 */
public final class UserContext {

    private static final ThreadLocal<String> CURRENT_USER = new ThreadLocal<>();
    private static final ThreadLocal<String> CURRENT_ROLE = new ThreadLocal<>();

    private UserContext() {
    }

    /** 写入当前用户与角色（仅认证过滤器调用） */
    public static void set(String userId, String role) {
        CURRENT_USER.set(userId);
        CURRENT_ROLE.set(role);
    }

    /** 读取当前用户；无认证上下文返回 null（调用方按匿名降级，勿在此抛异常） */
    public static String get() {
        return CURRENT_USER.get();
    }

    /** 读取当前角色；无认证上下文返回 null（RBAC 拦截器据此拒绝管理接口） */
    public static String role() {
        return CURRENT_ROLE.get();
    }

    /** 读取当前用户；无认证上下文抛 401（用于必须身份的业务入口的防御兜底） */
    public static String require() {
        String userId = CURRENT_USER.get();
        if (userId == null || userId.isBlank()) {
            throw new BizException(ErrorCode.UNAUTHORIZED, "未认证，请先登录");
        }
        return userId;
    }

    /** 清理（过滤器 finally 调用，防止线程池复用导致身份串号） */
    public static void clear() {
        CURRENT_USER.remove();
        CURRENT_ROLE.remove();
    }
}
