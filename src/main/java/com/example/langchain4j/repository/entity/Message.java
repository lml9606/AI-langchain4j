package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 消息实体：会话内的一轮对话。
 */
@Data
public class Message {

    public static final String ROLE_USER = "user";
    public static final String ROLE_ASSISTANT = "assistant";
    public static final String ROLE_SYSTEM = "system";

    private Long id;
    private String conversationId;
    private String role;
    private String content;
    private String model;
    private Integer tokenUsage;
    private OffsetDateTime createdAt;
}
