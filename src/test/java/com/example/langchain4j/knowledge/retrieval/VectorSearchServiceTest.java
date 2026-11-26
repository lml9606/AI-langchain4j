package com.example.langchain4j.knowledge.retrieval;

import com.example.langchain4j.auth.security.UserContext;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.knowledge.embedding.EmbeddingService;
import com.example.langchain4j.knowledge.embedding.SparseEmbeddingService;
import com.example.langchain4j.knowledge.entity.KnowledgeChunk;
import com.example.langchain4j.knowledge.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.doubleThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 混合检索单测：验证双通道融合顺序、单通道降级和指标上报值。 */
class VectorSearchServiceTest {

    private KnowledgeChunkMapper mapper;
    private EmbeddingService embeddingService;
    private SparseEmbeddingService sparseEmbeddingService;
    private AgentProperties properties;
    private AgentMetrics metrics;
    private VectorSearchService service;

    /** 每个用例使用独立 mock 和固定用户上下文，避免 ThreadLocal 串用。 */
    @BeforeEach
    void setUp() {
        mapper = mock(KnowledgeChunkMapper.class);
        embeddingService = mock(EmbeddingService.class);
        sparseEmbeddingService = mock(SparseEmbeddingService.class);
        metrics = mock(AgentMetrics.class);
        properties = new AgentProperties();
        properties.getRetrieval().setFusionPool(10);
        properties.getRetrieval().setDenseMinScore(0.85);
        properties.getRetrieval().setSparseMinScore(0.05);
        properties.getRetrieval().setRrfK(60);
        UserContext.set("user-1", "USER");
        service = new VectorSearchService(mapper, embeddingService, sparseEmbeddingService, properties, metrics);
    }

    /** 清理 ThreadLocal，防止影响同一 JVM 内的其他测试。 */
    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    /** 双通道命中同一块时 RRF 分数叠加，排序应优先共同命中的块。 */
    @Test
    void fusesDenseAndSparseHitsByRrfScore() {
        when(embeddingService.embed("q")).thenReturn(new float[]{1.0f, 0.0f});
        when(mapper.searchSimilar(any(float[].class), eq(10), eq(0.85), eq("user-1"), eq(false)))
                .thenReturn(List.of(chunk(1L, "dense-one"), chunk(2L, "dense-two")));
        when(sparseEmbeddingService.embed("q")).thenReturn("{1:1.0}/1048576");
        when(mapper.searchSparse(anyString(), eq(10), eq(0.05), eq("user-1"), eq(false)))
                .thenReturn(List.of(chunk(2L, "sparse-two"), chunk(3L, "sparse-three")));

        List<KnowledgeChunk> result = service.search("q", 2);

        assertThat(result).extracting(KnowledgeChunk::getId).containsExactly(2L, 1L);
        verify(metrics).recordRetrieval(eq(2), eq(2), eq(2),
                doubleThat(score -> score > 0.03 && score < 0.04), anyLong());
    }

    /** 稠密通道故障时仍应返回稀疏结果，并把 dense=0 的降级事实写入指标。 */
    @Test
    void fallsBackToSparseWhenDenseFails() {
        when(embeddingService.embed("q")).thenThrow(new IllegalStateException("embedding down"));
        when(sparseEmbeddingService.embed("q")).thenReturn("{1:1.0}/1048576");
        when(mapper.searchSparse(anyString(), eq(10), eq(0.05), eq("user-1"), eq(false)))
                .thenReturn(List.of(chunk(7L, "sparse-only")));

        List<KnowledgeChunk> result = service.search("q", 5);

        assertThat(result).extracting(KnowledgeChunk::getId).containsExactly(7L);
        verify(metrics).recordRetrieval(eq(0), eq(1), eq(1),
                doubleThat(score -> score > 0.016 && score < 0.017), anyLong());
    }

    /** 双通道都故障时返回空列表，但指标必须明确记录 empty，不能静默消失。 */
    @Test
    void returnsEmptyAndRecordsEmptyOutcomeWhenBothChannelsFail() {
        when(embeddingService.embed("q")).thenThrow(new IllegalStateException("dense down"));
        when(sparseEmbeddingService.embed("q")).thenThrow(new IllegalStateException("sparse down"));

        List<KnowledgeChunk> result = service.search("q", 5);

        assertThat(result).isEmpty();
        verify(metrics).recordRetrieval(eq(0), eq(0), eq(0), eq(0.0), anyLong());
    }

    /** 构造最小知识块：融合只依赖 id，内容仅用于断言可读性。 */
    private KnowledgeChunk chunk(long id, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setContent(content);
        return chunk;
    }
}
