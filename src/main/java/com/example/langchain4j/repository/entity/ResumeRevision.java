package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 简历优化记录：一次优化请求的底稿、改稿、变更清单与 diff。
 * <p>
 * 底稿与改稿都存全文：用户需要回看「改了什么」，diff 也需要原文才能重算；
 * 数据按 user_id 隔离，查询接口必须校验归属。
 */
@Data
public class ResumeRevision {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_DONE = "DONE";
    public static final String STATUS_ERROR = "ERROR";
    public static final String STATUS_SAVED = "SAVED";

    private Long id;
    /** 业务记录 ID（同时作为图执行的 threadId） */
    private String revisionId;
    /** 所属用户（数据隔离维度） */
    private String userId;
    /** 简历标题（展示用） */
    private String resumeTitle;
    /** 优化模式：TARGETED / FREE */
    private String mode;
    /** 目标岗位/选定章节/提示词快照（JSON 字符串） */
    private String targetJson;
    /** 简历原文（清洗前） */
    private String originalMd;
    /** 修改后的整份简历（Markdown） */
    private String revisedMd;
    /** 变更点清单（JSON 数组字符串） */
    private String changesJson;
    /** 行级 diff（JSON 数组字符串） */
    private String diffJson;
    /** RUNNING / DONE / ERROR / SAVED */
    private String status;
    /** 实际使用的模型名 */
    private String model;
    /** 生成消耗 token 总数 */
    private Integer tokenUsage;
    /** 失败原因（成功时为空） */
    private String error;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
