package com.example.langchain4j.dto;

import java.time.OffsetDateTime;

/**
 * 简历优化历史列表项：只给元信息，不带全文。
 */
public record ResumeRevisionSummary(
        String revisionId,
        String resumeTitle,
        String mode,
        String status,
        String model,
        Integer tokenUsage,
        OffsetDateTime createdAt) {
}
