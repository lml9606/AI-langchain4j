package com.example.langchain4j.tool.api;

/**
 * 工具执行上下文：数据隔离、审计与写操作授权的最小身份信息。
 * <p>
 * approved 只由确认执行链路置为 true；普通对话规划阶段必须保持 false，
 * 保证即使路由写错，写工具也会在执行层被拒绝。
 */
public record ToolContext(String userId, String conversationId, boolean approved, String idempotencyKey) {

    public ToolContext {
        userId = userId == null ? "" : userId;
        conversationId = conversationId == null ? "" : conversationId;
        idempotencyKey = idempotencyKey == null ? "" : idempotencyKey;
    }

    /** 只读调用便捷构造：默认未确认、无幂等键 */
    public ToolContext(String userId, String conversationId) {
        this(userId, conversationId, false, "");
    }
}
