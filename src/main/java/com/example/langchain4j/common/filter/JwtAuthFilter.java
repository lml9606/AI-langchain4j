package com.example.langchain4j.common.filter;

import com.example.langchain4j.common.auth.AuthUser;
import com.example.langchain4j.common.auth.JwtUtil;
import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.common.util.JsonUtil;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

/**
 * JWT 认证过滤器：Bearer Token 校验 + 身份上下文写入。
 * <p>
 * 策略（默认拒绝原则）：
 * <ul>
 *   <li>白名单放行：/api/auth/**（注册/登录）、/actuator/**（探针与 Prometheus 采集无凭证）；</li>
 *   <li>其余 /api/** 必须携带合法 Authorization: Bearer &lt;token&gt;，否则 401 直接响应（不进 Controller）；</li>
 *   <li>非 /api 资源（静态页等）不拦截，保持现有行为。</li>
 * </ul>
 * 过滤器内直接写 JSON 401（异常在过滤器链内不会被 {@code GlobalExceptionHandler} 捕获）。
 * 身份（userId + role）写入 {@link UserContext}，请求结束清理，防止 Tomcat 线程复用导致身份串号；
 * 角色授权由 {@link com.example.langchain4j.common.auth.RoleInterceptor} 在校验后逐接口收紧。
 */
@Slf4j
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class JwtAuthFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String AUTH_WHITELIST = "/api/auth";
    private static final String ACTUATOR_WHITELIST = "/actuator";

    private final JwtUtil jwtUtil;

    public JwtAuthFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        // 白名单与非 API 资源直接放行
        if (isWhitelisted(path) || !path.startsWith("/api/")) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        String token = (header != null && header.startsWith(BEARER_PREFIX))
                ? header.substring(BEARER_PREFIX.length()).trim()
                : null;
        // 防御 null（测试环境 mock 未 stub 时返回 null）
        Optional<AuthUser> parsed = jwtUtil.parse(token);
        if (parsed == null || parsed.isEmpty()) {
            log.warn("[auth] reject unauthenticated request, path={}", path);
            writeUnauthorized(response);
            return;
        }
        AuthUser authUser = parsed.get();
        UserContext.set(authUser.userId(), authUser.role());
        try {
            filterChain.doFilter(request, response);
        } finally {
            UserContext.clear();
        }
    }

    /** 白名单判断：前缀 + 边界校验（避免 /api/authx 之类被误放行） */
    private boolean isWhitelisted(String path) {
        return path.equals(AUTH_WHITELIST) || path.startsWith(AUTH_WHITELIST + "/")
                || path.equals(ACTUATOR_WHITELIST) || path.startsWith(ACTUATOR_WHITELIST + "/");
    }

    /** 401 响应（统一响应体，与全局异常处理风格一致） */
    private void writeUnauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JsonUtil.toJson(
                ApiResponse.error(ErrorCode.TOKEN_INVALID)));
    }
}
