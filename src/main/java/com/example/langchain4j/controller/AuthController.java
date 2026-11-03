package com.example.langchain4j.controller;

import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.LoginRequest;
import com.example.langchain4j.dto.LoginResponse;
import com.example.langchain4j.dto.RegisterRequest;
import com.example.langchain4j.service.AuthService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 认证接入层：注册与登录（JWT 签发）。
 * <p>
 * 路径在 JwtAuthFilter 白名单内（无需 Token）；后续请求携带返回的
 * Authorization: Bearer &lt;token&gt; 头访问业务接口。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    /** 注册：创建用户并直接签发 Token（注册即登录） */
    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    /** 登录：校验密码并签发 Token */
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.success(authService.login(request));
    }
}
