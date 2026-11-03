package com.example.langchain4j.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 认证配置（application.properties 中 auth.jwt.* 前缀）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "auth.jwt")
public class AuthProperties {

    /**
     * JWT 签名密钥（HS256，至少 256 bit）。
     * 默认值仅供本地开发；生产必须通过 JWT_SECRET 环境变量注入，否则所有 Token 可被伪造。
     */
    private String secret = "langchain4j-demo-jwt-secret-change-me-in-production-0123456789";

    /** Token 有效期（小时），过期后客户端需重新登录 */
    private int expireHours = 72;
}
