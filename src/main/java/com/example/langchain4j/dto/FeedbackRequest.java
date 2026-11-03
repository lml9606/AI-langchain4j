package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 反馈提交请求。
 */
public record FeedbackRequest(
        @NotBlank(message = "conversationId 不能为空")
        @Size(max = 64, message = "conversationId 长度不能超过 64")
        String conversationId,

        /** 被反馈的 assistant 消息 ID（可空） */
        Long messageId,

        @NotBlank(message = "rating 不能为空")
        @Pattern(regexp = "UP|DOWN", message = "rating 仅支持 UP / DOWN")
        String rating,

        /** 补充说明（点踩原因，可空） */
        @Size(max = 500, message = "comment 长度不能超过 500")
        String comment) {
}
