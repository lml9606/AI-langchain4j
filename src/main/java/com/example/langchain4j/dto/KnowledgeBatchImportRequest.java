package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 批量知识导入请求：一次提交多篇文档，逐篇父子分块落库（父块整章不嵌入 + 子块嵌入）。
 * <p>
 * 单篇失败不中断整批（服务层逐条 try-catch，返回逐条结果供重试失败项）。
 * 文档数上限由服务层按 agent.knowledge.batch-max-documents 配置再校验（此处 @Size 仅作第一道防线）。
 */
public record KnowledgeBatchImportRequest(
        @NotEmpty(message = "items 不能为空")
        @Size(max = 20, message = "单次最多导入 20 篇文档")
        List<Item> items) {

    /**
     * 单篇导入项：子块参数可空（空则用 agent.knowledge 默认配置；父块大小仅走配置）。
     */
    public record Item(
            @NotBlank(message = "content 不能为空")
            @Size(max = 20000, message = "content 长度不能超过 20000")
            String content,

            /** 元数据（来源、标签等），可空 */
            @Size(max = 2000, message = "metadata 长度不能超过 2000")
            String metadata,

            /** 子块大小（字符），空则用默认配置 */
            Integer chunkSize,

            /** 子块重叠（字符），空则用默认配置 */
            Integer chunkOverlap) {
    }
}
