package com.example.langchain4j.tool.run;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 多步写运行台账：一次用户确认对应一条 run。
 *
 * run_id 就是 confirmationId：令牌负责短期授权，run + ai_tool_execution 负责执行事实。
 * 每一步写操作都有独立执行台账（execution_id = confirmationId#stepNo），
 * 因此“第 1 步已提交、第 2 步失败”可以被准确记录为 PARTIAL_FAILED 并支持重试。
 */
@Data
public class TaskRun {

    /** 运行状态；数据库按枚举名存字符串。 */
    public enum Status {
        /** 已生成确认卡片，等待用户确认 */
        PENDING,
        /** 正在按步骤执行 */
        RUNNING,
        /** 全部步骤成功 */
        SUCCEEDED,
        /** 前面步骤已提交、后续步骤失败；重试会从失败步骤接续（已完成步骤幂等重放） */
        PARTIAL_FAILED,
        /** 没有任何步骤成功 */
        FAILED,
        /** 用户取消或新消息放弃 */
        CANCELLED
    }

    private String runId;
    private String userId;
    private String conversationId;
    private Status status;
    /** 已完成到最后一步的序号；失败时为失败步骤序号 */
    private Integer currentStep;
    private Integer totalSteps;
    /** 计划快照 JSON（每步工具与参数），用于重放和审计 */
    private String planJson;
    private String result;
    private String errorCode;
    private String errorMessage;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
