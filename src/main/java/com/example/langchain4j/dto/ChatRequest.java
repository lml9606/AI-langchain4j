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

    /** 指定模型名（如 qwen-max / deepseek-flash）；为空时使用服务端默认模型 */
    @Size(max = 64, message = "model 长度不能超过 64")
    private String model;

    /**
     * 回答模式：auto（标准链路，默认）/ deep（深度链路：多跳上限与检索候选数翻倍，适合复杂问题）；
     * 非法值由服务层归一为 auto。
     */
    @Size(max = 16, message = "answerMode 长度不能超过 16")
    private String answerMode;

    /**
     * 检索可见范围：all（公共库 + 本人库，默认）/ private（仅本人知识库）；
     * 非法值由服务层归一为 all。
     */
    @Size(max = 16, message = "visibility 长度不能超过 16")
    private String visibility;

    /**
     * 用户 ID（已废弃）：身份以 JWT 为准（服务端从 Authorization 头解析，忽略此字段）；
     * 保留仅为兼容旧客户端，可传任意值不影响鉴权结果。
     */
    private String userId;
}
