package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 对话请求。
 */
@Data
public class ChatRequest {

    @NotBlank(message = "query 不能为空")
    @Size(max = 4000, message = "query 长度不能超过 4000")
    private String query;

    /** 会话 ID；为空时由服务端新建会话 */
    private String conversationId;

    @NotBlank(message = "userId 不能为空")
    @Size(max = 64, message = "userId 长度不能超过 64")
    private String userId;
}
