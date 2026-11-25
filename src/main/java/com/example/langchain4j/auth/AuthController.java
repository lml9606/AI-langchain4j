package com.example.langchain4j.auth;

import com.example.langchain4j.auth.dto.LoginRequest;
import com.example.langchain4j.auth.dto.LoginResponse;
import com.example.langchain4j.auth.dto.RegisterRequest;
import com.example.langchain4j.common.response.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接入层：注册与登录（JWT 签发）。
 *
 * 路径在 JwtAuthFilter 白名单内（无需 Token）；后续请求携带登录返回的
 * Authorization 请求头（Bearer 令牌）访问业务接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /**
     * 注册：创建用户并直接签发 Token（注册即登录）。
     *
     * 特殊条件：用户名已存在时返回业务错误码（防枚举，与不存在账号登录错误同文案）。
     *
     * @param request 注册请求（用户名全局唯一；密码以 BCrypt 哈希落库，明文不存储）
     * @return 统一响应包裹的登录结果（token / 用户信息）
     */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    /**
     * 登录：校验密码（BCrypt 比对）并签发 Token。
     *
     * @param request 登录请求（用户名 + 明文密码，仅内存比对）
     * @return 统一响应包裹的登录结果（token / 用户信息）；凭证错误返回 401 业务码
     */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }
}
