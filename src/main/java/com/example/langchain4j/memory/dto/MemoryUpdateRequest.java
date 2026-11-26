package com.example.langchain4j.memory.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手动修改长期记忆的请求。
 *
 * 与新增接口的区别：这里允许调整重要度，也允许把候选记忆直接改成生效状态。
 */
public record MemoryUpdateRequest(
        @NotBlank(message = "content 不能为空")
        @Size(max = 1000, message = "content 最长 1000 字符")
        String content,
        @DecimalMin(value = "0.0", message = "importance 不能小于 0")
        @DecimalMax(value = "1.0", message = "importance 不能大于 1")
        Double importance) {
}
