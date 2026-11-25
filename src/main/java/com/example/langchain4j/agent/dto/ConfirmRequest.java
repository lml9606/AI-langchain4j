package com.example.langchain4j.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 写操作确认请求：confirmationId 来自上一轮 chat 响应的 pendingAction。
 */
@Data
public class ConfirmRequest {

    @NotBlank(message = "conversationId 不能为空")
    @Size(max = 64, message = "conversationId 长度不能超过 64")
    private String conversationId;

    @NotBlank(message = "confirmationId 不能为空")
    @Size(max = 128, message = "confirmationId 长度不能超过 128")
    private String confirmationId;

    /** true=确认执行；false=取消，不修改任何业务数据 */
    @NotNull(message = "approved 不能为空")
    private Boolean approved;
}
