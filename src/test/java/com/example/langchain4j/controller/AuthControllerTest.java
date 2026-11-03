package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.JwtUtil;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.dto.LoginRequest;
import com.example.langchain4j.dto.LoginResponse;
import com.example.langchain4j.dto.RegisterRequest;
import com.example.langchain4j.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 认证接口契约测试：锁定注册/登录的出入参结构与错误码契约（前端对接依据）。
 * 业务逻辑不在此验证（AuthService 整体 mock），只验证 Controller 层的 HTTP 契约。
 */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @MockitoBean
    private AuthService authService;
    /** JwtAuthFilter 随 WebMvcTest 加载（Filter 在扫描范围内），白名单路径不触发校验，仅需 Bean 存在 */
    @MockitoBean
    private JwtUtil jwtUtil;

    @Test
    @DisplayName("注册成功契约：code=0，返回 token/username/expireHours/role")
    void registerSuccessContract() throws Exception {
        when(authService.register(any(RegisterRequest.class)))
                .thenReturn(new LoginResponse("jwt-token-xxx", "zhangsan", 72, "USER"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"zhangsan","password":"secret66"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.token").value("jwt-token-xxx"))
                .andExpect(jsonPath("$.data.username").value("zhangsan"))
                .andExpect(jsonPath("$.data.expireHours").value(72))
                .andExpect(jsonPath("$.data.role").value("USER"));
    }

    @Test
    @DisplayName("登录密码错误契约：code=40101（统一响应体，不泄漏用户是否存在）")
    void loginWrongPasswordContract() throws Exception {
        when(authService.login(any(LoginRequest.class)))
                .thenThrow(new BizException(ErrorCode.INVALID_CREDENTIALS, ErrorCode.INVALID_CREDENTIALS.getMessage()));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"zhangsan","password":"wrong66"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(ErrorCode.INVALID_CREDENTIALS.getCode()));
    }

    @Test
    @DisplayName("参数校验契约：username 含非法字符 → HTTP 400 + code=40000")
    void validationFailureContract() throws Exception {
        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"bad name!","password":"secret66"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.BAD_REQUEST.getCode()));
    }
}
