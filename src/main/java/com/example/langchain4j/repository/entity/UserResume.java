package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户简历档案：对话内触发简历优化时取该档案作为简历原文。
 * <p>
 * 每用户仅一份（user_id 唯一，覆盖式 upsert）：避免把 2 万字原文塞进对话消息表，
 * 也让「上传一次、随时对话触发优化」成为可能。
 */
@Data
public class UserResume {

    private Long id;
    /** 所属用户（数据隔离维度，唯一约束） */
    private String userId;
    /** 简历标题（展示用） */
    private String title;
    /** 简历原文（Markdown/纯文本） */
    private String content;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
