package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 知识块实体：pgvector 向量检索的知识单元。
 */
@Data
public class KnowledgeChunk {

    private Long id;
    private String content;
    /** 向量（由 pgvector TypeHandler 映射为 float[]） */
    private float[] embedding;
    /** 元数据 JSON（来源、标签等） */
    private String metadata;
    private OffsetDateTime createdAt;
}
