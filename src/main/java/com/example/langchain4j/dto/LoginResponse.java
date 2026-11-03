package com.example.langchain4j.dto;

/**
 * 登录/注册响应：JWT Token + 有效期 + 角色。
 */
public record LoginResponse(
        /** Bearer Token（客户端后续请求放入 Authorization 头） */
        String token,
        /** 登录名（即各业务接口的 userId 维度） */
        String username,
        /** Token 有效期（小时） */
        int expireHours,
        /** 角色（USER / ADMIN；前端据此控制管理入口显隐，后端以拦截器为准） */
        String role) {
}
