package com.example.langchain4j.common.auth;

import com.example.langchain4j.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Date;
import java.util.Optional;

/**
 * JWT 签发与校验（HS256 对称签名）。
 * <p>
 * Token 载荷只放 userId（subject）与 role（claim），不放任何敏感信息（JWT 可被解码，只是不可篡改）。
 * 校验失败（签名不符/过期/格式错误/缺少 role）统一返回 empty，由过滤器转 401。
 * <p>
 * 权衡说明：角色签在 Token 里，角色变更需重新登录才生效（最长 expireHours）；
 * 企业级若要求即时撤销管理员，可改为每次请求查 ai_user 或在网关层做角色下发。
 */
@Component
public class JwtUtil {

    private static final String ROLE_CLAIM = "role";

    private final SecretKey key;
    private final int expireHours;

    public JwtUtil(AuthProperties properties) {
        // HS256 要求密钥 >= 256 bit；secret 过短会抛 WeakKeyException（启动即失败，避免带病运行）
        this.key = Keys.hmacShaKeyFor(properties.getSecret().getBytes(StandardCharsets.UTF_8));
        this.expireHours = properties.getExpireHours();
    }

    /** 为指定用户签发 Token（携带角色，供 RBAC 授权校验） */
    public String issue(String userId, String role) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(userId)
                .claim(ROLE_CLAIM, role)
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(expireHours, ChronoUnit.HOURS)))
                .signWith(key)
                .compact();
    }

    /**
     * 校验并解析 Token，返回其中的认证主体（userId + role）。
     *
     * @return 合法 Token 携带的 {@link AuthUser}；签名不符/已过期/格式错误/缺少 role 返回 empty
     */
    public Optional<AuthUser> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String subject = claims.getSubject();
            String role = claims.get(ROLE_CLAIM, String.class);
            if (subject == null || subject.isBlank() || role == null || role.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(new AuthUser(subject, role));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
