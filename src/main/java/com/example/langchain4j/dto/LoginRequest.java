package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 登录请求。
 */
public record LoginRequest(
        @NotBlank(message = "username 不能为空")
        @Size(max = 64, message = "username 长度不能超过 64")
        String username,

        @NotBlank(message = "password 不能为空")
        @Size(max = 64, message = "password 长度不能超过 64")
        String password) {
}
