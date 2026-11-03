package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 手动建单请求（流式路径或前端触发转人工时使用）。
 */
public record TicketRequest(
        @NotBlank(message = "conversationId 不能为空")
        @Size(max = 64, message = "conversationId 长度不能超过 64")
        String conversationId,

        @NotBlank(message = "question 不能为空")
        @Size(max = 1000, message = "question 长度不能超过 1000")
        String question,

        /** 检索根因（KNOWLEDGE_MISSING 等，可空） */
        String rootCause) {
}
