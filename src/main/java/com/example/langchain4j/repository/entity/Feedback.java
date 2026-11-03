package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 反馈实体：用户对回答的点赞/点踩（运营闭环信号）。
 */
@Data
public class Feedback {

    public static final String RATING_UP = "UP";
    public static final String RATING_DOWN = "DOWN";

    private Long id;
    /** 会话 ID（归属校验用） */
    private String conversationId;
    /** 反馈人 */
    private String userId;
    /** 被反馈的 assistant 消息 ID（可空） */
    private Long messageId;
    /** UP / DOWN */
    private String rating;
    /** 补充说明（点踩原因） */
    private String comment;
    private OffsetDateTime createdAt;
}
