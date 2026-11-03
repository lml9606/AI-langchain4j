package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 工单回复请求（运营侧）。
 */
public record TicketReplyRequest(
        @NotBlank(message = "reply 不能为空")
        @Size(max = 2000, message = "reply 长度不能超过 2000")
        String reply,

        /** 处理人（运营账号，可空） */
        @Size(max = 64, message = "assignee 长度不能超过 64")
        String assignee) {
}
