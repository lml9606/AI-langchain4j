package com.example.langchain4j.dto;

import lombok.Data;

import java.util.List;

/**
 * 知识检索响应。
 */
@Data
public class KnowledgeSearchResponse {

    private List<Chunk> chunks;

    /** 检索命中的知识块（不含向量，仅返回可读内容） */
    public record Chunk(String content, String metadata) {
    }

    public static KnowledgeSearchResponse of(List<Chunk> chunks) {
        KnowledgeSearchResponse response = new KnowledgeSearchResponse();
        response.chunks = chunks == null ? List.of() : chunks;
        return response;
    }
}
