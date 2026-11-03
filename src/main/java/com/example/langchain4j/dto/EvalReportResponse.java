package com.example.langchain4j.dto;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评测报告：批次汇总 + 单用例明细。
 */
public record EvalReportResponse(
        /** 批次 ID */
        String runId,
        /** 平均召回率 0~1（无关键词用例不参与均值；无可用指标时为空） */
        BigDecimal avgRecall,
        /** 平均忠实度 0~1（判分失败用例不参与均值；无可用指标时为空） */
        BigDecimal avgFaithfulness,
        /** 本批用例总数 */
        long total,
        /** 失败用例数 */
        long failed,
        /** 单用例明细 */
        List<Item> items) {

    public record Item(Long caseId,
                       String question,
                       BigDecimal recallScore,
                       BigDecimal faithfulness,
                       String status,
                       String errorMsg,
                       String retrievedCtx,
                       String answer,
                       OffsetDateTime createdAt) {
    }
}
