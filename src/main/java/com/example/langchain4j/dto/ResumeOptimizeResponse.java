package com.example.langchain4j.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 简历优化响应：改稿 + 变更清单 + 行级 diff。
 * <p>
 * originalMarkdown 只在历史详情接口返回；优化接口不重复回传客户端刚提交的原文。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ResumeOptimizeResponse(
        String revisionId,
        String resumeTitle,
        String mode,
        String model,
        String status,
        String originalMarkdown,
        String revisedMarkdown,
        List<ResumeChangeDto> changes,
        List<ResumeDiffBlockDto> diff,
        Integer tokenUsage,
        boolean truncated,
        String error,
        OffsetDateTime createdAt) {
}
