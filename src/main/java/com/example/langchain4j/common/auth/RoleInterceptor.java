package com.example.langchain4j.common.auth;

import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.common.util.JsonUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * 角色鉴权拦截器：校验 Handler 上的 {@link RequireRole} 注解。
 * <p>
 * 职责边界（与 {@code JwtAuthFilter} 分离）：
 * <ul>
 *   <li>Filter 负责认证（Token 是否合法 → 401），拦截器负责授权（角色是否足够 → 403）；</li>
 *   <li>注解解析：方法级优先于类级（类整体收口、个别方法放开）；</li>
 *   <li>未标注注解的接口放行（认证已在 Filter 完成，此处只做逐点收紧）；</li>
 *   <li>非 Controller 处理器（资源请求等）直接放行。</li>
 * </ul>
 * 拦截器内直接写 JSON 403（拦截器抛出的异常不会进入 {@code GlobalExceptionHandler}，
 * 且角色不足属于协议层拒绝，与业务异常语义分离）。
 */
@Slf4j
@Component
public class RoleInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
            throws IOException {
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }
        RequireRole required = handlerMethod.getMethodAnnotation(RequireRole.class);
        if (required == null) {
            required = handlerMethod.getBeanType().getAnnotation(RequireRole.class);
        }
        if (required == null) {
            return true;
        }
        String actualRole = UserContext.role();
        if (required.value().name().equals(actualRole)) {
            return true;
        }
        log.warn("[rbac] role denied, path={}, required={}, actual={}",
                request.getRequestURI(), required.value(), actualRole);
        writeForbidden(response);
        return false;
    }

    /** 403 响应（统一响应体，与 JwtAuthFilter 的 401 风格一致） */
    private void writeForbidden(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(JsonUtil.toJson(ApiResponse.error(ErrorCode.FORBIDDEN)));
    }
}
