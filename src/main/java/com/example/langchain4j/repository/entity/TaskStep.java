package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 任务步骤：一次工具调用的持久化状态。
 *
 * 每步单独记录状态和结果，目的是让任务可以从失败步骤继续，而不是整轮重跑。
 * 写步骤的 {@code idempotencyKey = runId + ':' + stepNo}，
 * 与 {@code ai_tool_execution} 的幂等键严格对应。
 */
@Data
public class TaskStep {

    public enum Status {
        PENDING,
        RUNNING,
        SUCCEEDED,
        FAILED,
        /** 前序步骤失败，本步不再执行 */
        SKIPPED,
        CANCELLED
    }

    private Long id;
    private String runId;
    private int stepNo;
    private String stageId;
    private String toolName;
    /** READ_ONLY / WRITE，来自 ToolDefinition，不能由模型决定 */
    private String sideEffect;
    private String argsJson;
    private String purpose;
    private Status status;
    private String resultSummary;
    /** 工具返回的结构化数据，后续步骤的 $ref 从这里取值 */
    private String resultJson;
    /** 工具证据快照，确认执行后回答节点恢复引用时使用 */
    private String evidenceJson;
    private String errorCode;
    private String errorMessage;
    private Long costMs;
    private int attempts;
    private OffsetDateTime startedAt;
    private OffsetDateTime finishedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public boolean write() {
        return "WRITE".equalsIgnoreCase(sideEffect);
    }
}
