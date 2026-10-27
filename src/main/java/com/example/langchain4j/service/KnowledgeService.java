package com.example.langchain4j.service;

import com.example.langchain4j.dto.KnowledgeImportRequest;
import com.example.langchain4j.dto.KnowledgeSearchResponse;
import com.example.langchain4j.knowledge.EmbeddingService;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.repository.entity.KnowledgeChunk;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 知识服务：知识导入（嵌入 + 落库）与语义检索（演示 pgvector 全链路）。
 */
@Slf4j
@Service
public class KnowledgeService {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 10;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;

    public KnowledgeService(KnowledgeChunkMapper knowledgeChunkMapper,
                            EmbeddingService embeddingService,
                            VectorSearchService vectorSearchService) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.embeddingService = embeddingService;
        this.vectorSearchService = vectorSearchService;
    }

    /**
     * 导入单条知识：文本嵌入后写入 pgvector。
     *
     * @return 新知识块 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public long importChunk(KnowledgeImportRequest request) {
        String content = sanitize(request.getContent());
        float[] embedding = embeddingService.embed(content);
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setContent(content);
        chunk.setEmbedding(embedding);
        chunk.setMetadata(request.getMetadata());
        knowledgeChunkMapper.insert(chunk);
        log.info("[knowledge] chunk imported, id={}, length={}", chunk.getId(), content.length());
        return chunk.getId();
    }

    /** 语义检索（topK 有界防御），检索失败降级为空列表由上游处理 */
    public KnowledgeSearchResponse search(String query, int topK) {
        int bounded = Math.min(Math.max(topK, 1), MAX_TOP_K);
        List<KnowledgeChunk> chunks = vectorSearchService.search(query, bounded);
        List<KnowledgeSearchResponse.Chunk> result = chunks.stream()
                .map(c -> new KnowledgeSearchResponse.Chunk(c.getContent(), c.getMetadata()))
                .toList();
        return KnowledgeSearchResponse.of(result);
    }

    public KnowledgeSearchResponse search(String query) {
        return search(query, DEFAULT_TOP_K);
    }

    /** 控制字符清洗 + 边界空白清理（嵌入前的防御性处理） */
    private String sanitize(String content) {
        return content.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
