package com.example.langchain4j.agent.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Agent 工作流断点恢复请求（checkpoint resume），与具体业务功能无关。
 */
public record AgentResumeRequest(
        @NotBlank(message = "conversationId 不能为空")
        @Size(max = 64, message = "conversationId 长度不能超过 64")
        String conversationId) {
}