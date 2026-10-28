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
    /** 业务会话 ID（同时作为 LangGraph 的 threadId，checkpoint 按此隔离） */
    private String conversationId;
    /** 所属用户 */
    private String userId;
    /** 会话标题（取首条用户消息截断生成） */
    private String title;
    /** 会话状态：ACTIVE / CLOSED（STATUS_* 常量） */
    private String status;
    /** 创建时间 */
    private OffsetDateTime createdAt;
    /** 更新时间 */
    private OffsetDateTime updatedAt;
}
