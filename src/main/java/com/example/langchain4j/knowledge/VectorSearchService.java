package com.example.langchain4j.knowledge;

import com.example.langchain4j.repository.entity.KnowledgeChunk;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 向量检索服务：pgvector 语义检索，检索失败不阻断主流程（返回空列表由上游降级）。
 */
@Slf4j
@Service
public class VectorSearchService {

    private static final int DEFAULT_TOP_K = 3;
    /** 最小相似度阈值：pgvector 余弦距离越接近 1 越相似，低于 0.3 视为不相关直接丢弃 */
    private static final double DEFAULT_MIN_SCORE = 0.3;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingService embeddingService;

    public VectorSearchService(KnowledgeChunkMapper knowledgeChunkMapper, EmbeddingService embeddingService) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.embeddingService = embeddingService;
    }

    /**
     * 语义检索相关知识块。
     * <p>
     * 流程：查询文本先经嵌入模型转向量，再用 pgvector 余弦距离在知识表中取
     * topK 条相似块（相似度低于阈值的自动过滤）。
     *
     * @param query 查询文本
     * @return 相关块列表；任何环节失败均返回空列表（fail-open，由调用方降级）
     */
    public List<KnowledgeChunk> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            float[] queryVector = embeddingService.embed(query);
            return knowledgeChunkMapper.searchSimilar(queryVector, Math.max(1, topK), DEFAULT_MIN_SCORE);
        } catch (Exception e) {
            log.warn("[vector] search degraded, query={}, reason={}", query, e.getMessage());
            return List.of();
        }
    }

    public List<KnowledgeChunk> search(String query) {
        return search(query, DEFAULT_TOP_K);
    }

    /** 拼接检索上下文；无结果返回空串，供 prompt 组装降级 */
    public String buildContext(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            if (chunk == null || chunk.getContent() == null) {
                continue;
            }
            sb.append("【参考资料 ").append(i + 1).append("】")
              .append(chunk.getContent()).append('\n');
        }
        return sb.toString();
    }
}
