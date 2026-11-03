package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求。
 */
public record RegisterRequest(
        @NotBlank(message = "username 不能为空")
        @Size(max = 64, message = "username 长度不能超过 64")
        @Pattern(regexp = "^[a-zA-Z0-9_-]+$", message = "username 只能包含字母、数字、下划线、连字符")
        String username,

        @NotBlank(message = "password 不能为空")
        @Size(min = 6, max = 64, message = "password 长度需在 6~64 之间")
        String password) {
}
