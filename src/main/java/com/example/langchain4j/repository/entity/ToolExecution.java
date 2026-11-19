package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 工具执行台账：写工具幂等与结果查询的唯一事实源。
 *
 * <p>{@code (userId, executionId)} 唯一，{@code executionId} 就是确认令牌 ID。
 * 业务写入与这条台账必须在同一个数据库事务里提交：
 * <ul>
 *   <li>提交成功 → 台账 SUCCEEDED 且业务已变更，重放直接返回结果；</li>
 *   <li>回滚 → 台账 RUNNING 也一起回滚，重试可安全重新执行。</li>
 * </ul>
 */
@Data
public class ToolExecution {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCEEDED = "SUCCEEDED";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    /** 幂等键（当前实现 = confirmationId） */
    private String executionId;
    private String userId;
    private String conversationId;
    private String tool;
    /** 参数指纹：同一幂等键换了参数直接拒绝，防止令牌被错误复用 */
    private String argsHash;
    /** RUNNING / SUCCEEDED / FAILED */
    private String status;
    /** 成功结果（重放时原样返回） */
    private String result;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
