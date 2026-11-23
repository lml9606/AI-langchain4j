package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 工具确认/执行台账：从待确认到执行结束的唯一业务事实源。
 *
 * {@code (userId, executionId)} 唯一，{@code executionId} 就是确认令牌 ID。
 * 创建确认动作时先写 PENDING；用户确认执行后由工具流转到 RUNNING，最终到
 * SUCCEEDED / FAILED / CANCELLED。业务写入与执行状态更新必须在同一个数据库事务里提交：
 *
 * - 提交成功 → 台账 SUCCEEDED 且业务已变更，重放直接返回结果；
 * - 回滚 → 台账 RUNNING 也一起回滚，重试可安全重新执行。
 *
 */
@Data
public class ToolExecution {

    /** 写操作台账状态；数据库仍按枚举名存字符串，便于检索和兼容存量数据。 */
    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        CANCELLED
    }

    private Long id;
    /** 幂等键（旧版单写 = confirmationId；多步骤 = runId:stepNo） */
    private String executionId;
    private String userId;
    private String conversationId;
    private String tool;
    /** 参数指纹：同一幂等键换了参数直接拒绝，防止令牌被错误复用 */
    private String argsHash;
    /** 本次执行的工具参数；PENDING 阶段为待确认参数，RUNNING 时覆盖为实际执行参数 */
    private String argsJson;
    /** 待确认计划快照；创建确认动作时写入 */
    private String planJson;
    /** 确认卡片摘要 */
    private String summary;
    /** READ_ONLY / WRITE 等风险等级 */
    private String risk;
    /** 待确认动作过期时间（展示/审计用；Redis TTL 是运行时过期事实源） */
    private OffsetDateTime expiresAt;
    /** 当前执行状态；MyBatis 使用 EnumTypeHandler 按枚举名读写。 */
    private Status status;
    /** 已接管执行次数（含 PENDING 首跑、FAILED 重试和僵尸 RUNNING 接管） */
    private Integer attemptCount;
    /** 最近一次进入 RUNNING 的时间 */
    private OffsetDateTime startedAt;
    /** 最近一次进入终态（SUCCEEDED / FAILED / CANCELLED）的时间 */
    private OffsetDateTime finishedAt;
    /** 成功结果（重放时原样返回） */
    private String result;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
