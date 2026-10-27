package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 会话实体：conversationId 同时作为 LangGraph 的 threadId，实现会话级 Checkpoint 隔离。
 */
@Data
public class Conversation {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_CLOSED = "CLOSED";

    private Long id;
    private String conversationId;
    private String userId;
    private String title;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
