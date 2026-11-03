package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 工单实体：转人工自动建单，运营回复后结单。
 */
@Data
public class Ticket {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_CLOSED = "CLOSED";

    private Long id;
    /** 工单号（展示用） */
    private String ticketNo;
    /** 提单用户 */
    private String userId;
    /** 触发转人工的会话 */
    private String conversationId;
    /** 触发转人工的问题 */
    private String question;
    /** 检索根因（KNOWLEDGE_MISSING 等，可空） */
    private String rootCause;
    /** OPEN / RESOLVED / CLOSED */
    private String status;
    /** 处理人（运营侧） */
    private String assignee;
    /** 运营回复（知识补录后告知用户） */
    private String reply;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    private OffsetDateTime resolvedAt;
}
