package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 任务运行实例：一次多步骤业务任务的唯一标识和进度游标。
 *
 * 它不是审计表，而是执行事实源：
 *
 * - {@code currentStep} 记录下一次应该从哪一步继续；
 * - {@code status} 记录任务处于规划、待确认、执行中还是终态；
 * - {@code leaseOwner/leaseUntil} 防止多实例同时执行同一个 run；
 * - {@code confirmationId} 把"用户确认过的批量写操作"和这条 run 绑定。
 *
 */
@Data
public class TaskRun {

    /** 任务状态；数据库按枚举名存字符串，便于排查和兼容存量数据。 */
    public enum Status {
        /** 计划已生成，只读步骤可能已执行，尚未进入确认 */
        PLANNING,
        /** 写步骤已生成确认令牌，等待用户确认 */
        WAITING_CONFIRM,
        /** 已获得执行权，正在按步骤推进 */
        RUNNING,
        /** 所有步骤成功 */
        SUCCEEDED,
        /** 部分成功（为后续"允许部分成功"预留，当前遇错即停） */
        PARTIAL_FAILED,
        /** 某一步失败，后续步骤已跳过 */
        FAILED,
        /** 用户取消或会话被新消息打断 */
        CANCELLED
    }

    private String id;
    private String conversationId;
    private String userId;
    private String workflowId;
    private Status status;
    private int currentStep;
    private String planJson;
    private String planHash;
    private String confirmationId;
    /** 最终回答快照：确认令牌过期后重放仍能返回同一结果 */
    private String result;
    /** 终态回答是否已写入会话消息（恢复 worker 幂等补偿标记） */
    private boolean replySaved;
    private String leaseOwner;
    private OffsetDateTime leaseUntil;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** 终态任务不再重新执行，只允许查询和重放结果。 */
    public boolean terminal() {
        return status == Status.SUCCEEDED
                || status == Status.PARTIAL_FAILED
                || status == Status.CANCELLED;
    }
}
