package com.example.langchain4j.auth.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.method.HandlerMethod;

import java.io.PrintWriter;
import java.io.StringWriter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** 角色授权测评：验证管理员接口只接受 ADMIN，普通用户得到 403。 */
@ExtendWith(MockitoExtension.class)
class RoleInterceptorTest {

    @Mock
    private HttpServletRequest request;
    @Mock
    private HttpServletResponse response;
    @Mock
    private HandlerMethod handler;
    @Mock
    private RequireRole requireRole;

    private RoleInterceptor interceptor;
    private StringWriter responseBody;

    /** 构造拦截器和响应缓冲区。 */
    @BeforeEach
    void setUp() {
        interceptor = new RoleInterceptor();
        responseBody = new StringWriter();
    }

    /** 清理请求身份，避免测试线程复用污染。 */
    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** ADMIN 访问带 ADMIN 注解的方法应放行。 */
    @Test
    void adminRolePasses() throws Exception {
        prepareAnnotation(UserRole.ADMIN);
        UserContext.set("admin", "ADMIN");

        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
    }

    /** 普通用户访问管理方法应返回 403，并写统一错误体。 */
    @Test
    void userRoleIsForbidden() throws Exception {
        prepareAnnotation(UserRole.ADMIN);
        UserContext.set("alice", "USER");
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));

        assertThat(interceptor.preHandle(request, response, handler)).isFalse();
        assertThat(responseBody.toString()).contains("\"code\":40300");
    }

    /** 未标注角色的方法只依赖认证，不额外拒绝普通用户。 */
    @Test
    @SuppressWarnings("unchecked")
    void unannotatedMethodPasses() throws Exception {
        when(handler.getMethodAnnotation(RequireRole.class)).thenReturn(null);
        when(handler.getBeanType()).thenReturn((Class) Object.class);
        UserContext.set("alice", "USER");

        assertThat(interceptor.preHandle(request, response, handler)).isTrue();
    }

    /** 非 Controller Handler 不属于角色拦截范围。 */
    @Test
    void nonHandlerMethodPasses() throws Exception {
        assertThat(interceptor.preHandle(request, response, new Object())).isTrue();
    }

    /** 为 HandlerMethod 模拟方法级 ADMIN 注解。 */
    private void prepareAnnotation(UserRole role) {
        when(handler.getMethodAnnotation(RequireRole.class)).thenReturn(requireRole);
        when(requireRole.value()).thenReturn(role);
    }
}
