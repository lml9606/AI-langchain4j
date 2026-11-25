package com.example.langchain4j.memory.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手动新增/修正长期记忆的请求。
 *
 * 提供给用户纠错入口：记忆被错误抽取时，用户可以覆盖内容或删除，避免错误长期放大。
 */
public record MemoryCreateRequest(
        @NotBlank(message = "memoryType 不能为空")
        String memoryType,
        @NotBlank(message = "memoryKey 不能为空")
        @Size(max = 64, message = "memoryKey 最长 64 字符")
        String memoryKey,
        @NotBlank(message = "content 不能为空")
        @Size(max = 1000, message = "content 最长 1000 字符")
        String content,
        @DecimalMin(value = "0.0", message = "importance 不能小于 0")
        @DecimalMax(value = "1.0", message = "importance 不能大于 1")
        Double importance) {
}
