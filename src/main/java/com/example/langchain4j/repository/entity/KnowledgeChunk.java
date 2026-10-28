package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 知识块实体：pgvector 向量检索的知识单元。
 */
@Data
public class KnowledgeChunk {

    private Long id;
    /** 知识块内容（切分后的文本片段） */
    private String content;
    /** 向量（pgvector 由 VectorTypeHandler 映射为 float[]，1024 维 BGE-M3） */
    private float[] embedding;
    /** 元数据 JSON（来源、标签等扩展信息） */
    private String metadata;
    /** 创建时间 */
    private OffsetDateTime createdAt;
}
