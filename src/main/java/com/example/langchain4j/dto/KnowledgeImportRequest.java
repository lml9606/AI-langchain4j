package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 知识导入请求：单条知识块导入（演示 pgvector 嵌入 + 检索链路）。
 */
@Data
public class KnowledgeImportRequest {

    @NotBlank(message = "content 不能为空")
    @Size(max = 20000, message = "content 长度不能超过 20000")
    private String content;

    /** 元数据（来源、标签等），可空 */
    @Size(max = 2000, message = "metadata 长度不能超过 2000")
    private String metadata;
}
