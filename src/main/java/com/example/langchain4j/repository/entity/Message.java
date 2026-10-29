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
    /** 所属会话 ID（同时是 LangGraph threadId） */
    private String conversationId;
    /** 消息角色：user / assistant / system（ROLE_* 常量） */
    private String role;
    /** 消息内容：始终存原始对话，改写结果只作为图执行输入不入库 */
    private String content;
    /** 该条消息使用的模型名 */
    private String model;
    /** 该条消息消耗的 token 数（可空） */
    private Integer tokenUsage;
    /** 话题段归属（段号；user 落库后回填，assistant 继承同轮 user；可空=历史数据未回填） */
    private Integer segmentId;
    /** 创建时间 */
    private OffsetDateTime createdAt;
}
