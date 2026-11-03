package com.example.langchain4j.config;

import com.example.langchain4j.common.auth.RoleInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Web MVC 配置。
 * <p>
 * SSE 说明：SseEmitter 超时由各接口自行控制（chat/stream 用 0L 永不超时 + 心跳保活），
 * 无需全局 async request-timeout；此处配置 CORS（演示放开，生产按域名收紧）与
 * RBAC 拦截器（{@link RoleInterceptor} 校验 @RequireRole 注解，401 认证由 JwtAuthFilter 负责）。
 */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final RoleInterceptor roleInterceptor;

    public WebMvcConfig(RoleInterceptor roleInterceptor) {
        this.roleInterceptor = roleInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 只拦 /api/**（静态资源与非 API 路径不经过拦截器）
        registry.addInterceptor(roleInterceptor).addPathPatterns("/api/**");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOriginPatterns("*")
                .allowedMethods("GET", "POST", "OPTIONS")
                .allowedHeaders("*")
                .maxAge(3600);
    }
}
