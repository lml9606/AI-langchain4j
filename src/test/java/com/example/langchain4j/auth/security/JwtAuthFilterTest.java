package com.example.langchain4j.auth.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** 认证边界测评：验证受保护接口默认拒绝、白名单放行和身份上下文清理。 */
@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock
    private JwtUtil jwtUtil;
    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private FilterChain chain;

    private JwtAuthFilter filter;
    private StringWriter responseBody;

    /** 每个用例使用独立过滤器与响应缓冲区。 */
    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtUtil);
        responseBody = new StringWriter();
    }

    /** 清理线程上下文，避免身份残留。 */
    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 受保护接口缺少 Token 时直接返回 401，不进入 Controller 链。 */
    @Test
    void protectedApiWithoutTokenReturnsUnauthorized() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/agent/chat");
        when(request.getHeader("Authorization")).thenReturn(null);
        when(jwtUtil.parse(null)).thenReturn(Optional.empty());
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
        assertThat(responseBody.toString()).contains("\"code\":40102");
    }

    /** 合法 Token 在请求期间写入身份，并在 finally 中彻底清理。 */
    @Test
    void validTokenSetsAndClearsUserContext() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/agent/chat");
        when(request.getHeader("Authorization")).thenReturn("Bearer valid-token");
        when(jwtUtil.parse("valid-token"))
                .thenReturn(Optional.of(new AuthUser("alice", "USER")));
        doAnswer(invocation -> {
            assertThat(UserContext.get()).isEqualTo("alice");
            assertThat(UserContext.role()).isEqualTo("USER");
            return null;
        }).when(chain).doFilter(request, response);

        filter.doFilterInternal(request, response, chain);

        assertThat(UserContext.get()).isNull();
        assertThat(UserContext.role()).isNull();
    }

    /** 登录接口属于白名单，不应要求 Authorization。 */
    @Test
    void authWhitelistPassesWithoutToken() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/auth/login");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtUtil);
    }

    /** 带 Bearer 前缀但令牌无效时仍返回 401。 */
    @Test
    void invalidBearerTokenReturnsUnauthorized() throws Exception {
        when(request.getRequestURI()).thenReturn("/api/knowledge/search");
        when(request.getHeader("Authorization")).thenReturn("Bearer expired");
        when(jwtUtil.parse("expired")).thenReturn(Optional.empty());
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));

        filter.doFilterInternal(request, response, chain);

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        verify(chain, never()).doFilter(any(), any());
    }

    /** 非 API 静态资源不进入认证拦截，保持原有静态页面行为。 */
    @Test
    void nonApiPathPassesWithoutToken() throws Exception {
        when(request.getRequestURI()).thenReturn("/doc.html");

        filter.doFilterInternal(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(jwtUtil);
    }
}
