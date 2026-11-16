package com.example.langchain4j.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 写操作确认请求：confirmationId 来自上一轮 chat 响应的 pendingAction。
 */
@Data
public class ConfirmRequest {

    @NotBlank(message = "confirmationId 不能为空")
    @Size(max = 128, message = "confirmationId 长度不能超过 128")
    private String confirmationId;

    /** true=确认执行；false=取消，不修改任何业务数据 */
    private boolean approved;
}
