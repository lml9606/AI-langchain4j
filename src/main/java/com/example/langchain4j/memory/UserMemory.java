package com.example.langchain4j.memory;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 用户长期记忆：跨会话稳定偏好/事实，不是对话历史。
 *
 * 同一用户 + 类型 + key 唯一，同一含义反复出现时覆盖更新，避免记忆无限膨胀。
 * 记忆只作为生成参考，与当前问题或检索资料冲突时必须以当前信息为准。
 */
@Data
public class UserMemory {

    /** 用户偏好（回答风格、格式、称呼等） */
    public static final String TYPE_PREFERENCE = "PREFERENCE";
    /** 稳定事实（所属组织、角色、常用编号等） */
    public static final String TYPE_FACT = "FACT";
    /** 持续生效的任务约束（篇幅、口径、流程要求等） */
    public static final String TYPE_TASK_CONSTRAINT = "TASK_CONSTRAINT";

    private Long id;
    private String userId;
    /** PREFERENCE / FACT / TASK_CONSTRAINT */
    private String memoryType;
    /** 稳定短标签：同一含义必须复用同一 key（如 answer_style） */
    private String memoryKey;
    /** 一句话事实，可直接展示给用户查看和纠错 */
    private String content;
    /** 0~1，召回排序用 */
    private Double importance;
    private String sourceConversationId;
    /** null 表示永久有效 */
    private OffsetDateTime expiresAt;
    private OffsetDateTime lastUsedAt;
    private Integer hitCount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
