package com.example.langchain4j.common.auth;

/**
 * JWT 解析出的认证主体：用户身份 + 角色。
 * <p>
 * 载荷最小化：只放 username（业务隔离维度）与 role（授权维度），
 * 不放密码、邮箱等敏感信息（JWT 可被 base64 解码，只是不可篡改）。
 */
public record AuthUser(
        /** 登录名（即各业务表的 user_id 维度） */
        String userId,
        /** 角色（USER/ADMIN，签发时取自 ai_user.role） */
        String role) {
}
