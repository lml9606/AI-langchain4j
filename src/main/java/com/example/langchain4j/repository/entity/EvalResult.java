package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 评测结果实体：每批次（run_id）每用例一行，核心指标为召回率与忠实度。
 */
@Data
public class EvalResult {

    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    /** 评测批次 ID（一次 runEval 共用） */
    private String runId;
    /** 用例 ID */
    private Long caseId;
    /** 评测问题 */
    private String question;
    /** 检索到的上下文（截断存储） */
    private String retrievedCtx;
    /** 基于检索上下文生成的答案 */
    private String answer;
    /** 召回率 0~1（期望关键词在检索上下文命中比例；无关键词为空） */
    private BigDecimal recallScore;
    /** 忠实度 0~1（LLM 判分；判分失败为空） */
    private BigDecimal faithfulness;
    /** SUCCESS / FAILED */
    private String status;
    private String errorMsg;
    private OffsetDateTime createdAt;
}
