package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Prompt 保存请求（配置化版本化：保存后热生效）。
 */
public record PromptSaveRequest(
        @NotBlank(message = "key 不能为空")
        @Size(max = 64, message = "key 长度不能超过 64")
        String key,

        @NotBlank(message = "text 不能为空")
        String text) {
}
