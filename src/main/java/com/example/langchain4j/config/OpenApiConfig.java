package com.example.langchain4j.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 文档配置（springdoc-openapi + Swagger UI）。
 * <p>
 * 访问路径：/swagger-ui.html（交互式调试页）与 /v3/api-docs（OpenAPI JSON）。
 * 认证说明：除 /api/auth/** 与 /actuator/** 外，全部接口需 JWT——先调
 * /api/auth/register 注册、/api/auth/login 登录拿到 token，点 UI 右上角
 * Authorize 填入（无需 Bearer 前缀，UI 自动拼接）即可调试其余接口。
 * <p>
 * 路径白名单说明：JwtAuthFilter 只拦 /api/**，/swagger-ui/** 与 /v3/api-docs/**
 * 不以 /api/ 开头，天然放行，无需改过滤器。
 */
@Configuration
public class OpenApiConfig {

    private static final String SECURITY_SCHEME = "BearerAuth";

    @Bean
    public OpenAPI agentOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("AI Agent 客服知识问答 API")
                        .description("LangGraph4j 生产级 Agent 框架接口文档："
                                + "先调 /api/auth/login 拿 Bearer Token，"
                                + "点右上角 Authorize 填入后即可调试全部接口")
                        .version("1.0.0"))
                .addSecurityItem(new SecurityRequirement().addList(SECURITY_SCHEME))
                .components(new Components()
                        .addSecuritySchemes(SECURITY_SCHEME, new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("登录接口返回的 token（不含 Bearer 前缀）")));
    }
}
