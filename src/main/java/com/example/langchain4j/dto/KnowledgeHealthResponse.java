package com.example.langchain4j.dto;

import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;

import java.time.OffsetDateTime;

/**
 * 知识库健康度：当前用户可见范围（公共库 + 本人私有库）的聚合观测。
 * 无向量块 = 嵌入失败残留（应重嵌/清理）；空内容块 = 脏数据（应清理）。
 */
public record KnowledgeHealthResponse(
        long totalChunks,
        long privateChunks,
        long publicChunks,
        long emptyContent,
        long noEmbedding,
        double avgContentLength,
        OffsetDateTime lastImportedAt) {

    public static KnowledgeHealthResponse from(KnowledgeChunkMapper.HealthStats stats) {
        if (stats == null) {
            return new KnowledgeHealthResponse(0, 0, 0, 0, 0, 0, null);
        }
        return new KnowledgeHealthResponse(
                stats.getTotalChunks(),
                stats.getPrivateChunks(),
                stats.getPublicChunks(),
                stats.getEmptyContent(),
                stats.getNoEmbedding(),
                stats.getAvgContentLength(),
                stats.getLastImportedAt());
    }
}
