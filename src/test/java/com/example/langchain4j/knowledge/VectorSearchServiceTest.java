package com.example.langchain4j.knowledge;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.KnowledgeChunk;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * 父子分块装配单测：命中子块 → 父块整章（同 title 相邻组拼接）、孤儿子块退化、
 * 同父块去重、预算截断与 fail-open 降级是检索装配链路的核心行为，必须覆盖。
 */
@ExtendWith(MockitoExtension.class)
class VectorSearchServiceTest {

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private AgentProperties properties;

    private VectorSearchService service;

    @BeforeEach
    void setUp() {
        AgentProperties.Retrieval retrieval = new AgentProperties.Retrieval();
        retrieval.setContextBudget(1000);
        // lenient：空命中用例不读预算配置，避免严格模式报不必要的 stub
        lenient().when(properties.getRetrieval()).thenReturn(retrieval);
        service = new VectorSearchService(knowledgeChunkMapper, embeddingService, properties);
    }

    /** 命中子块（parentId 非空） */
    private KnowledgeChunk hit(long id, long parentId) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setParentId(parentId);
        chunk.setContent("命中子块原文" + id);
        return chunk;
    }

    /** 父块（含 title） */
    private KnowledgeChunk parent(long id, String title, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setChunkType("PARENT");
        chunk.setTitle(title);
        chunk.setContent(content);
        return chunk;
    }

    @Test
    @DisplayName("空命中列表 → 装配为空")
    void assembleEmptyHits() {
        assertThat(service.assemble(List.of())).isEmpty();
        assertThat(service.assemble(null)).isEmpty();
    }

    @Test
    @DisplayName("孤儿子块（存量 parentId=null）→ 退化为子块原文")
    void assembleOrphanChild() {
        KnowledgeChunk orphan = new KnowledgeChunk();
        orphan.setId(1L);
        orphan.setContent("孤儿子块原文");

        List<KnowledgeChunk> result = service.assemble(List.of(orphan));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("孤儿子块原文");
    }

    @Test
    @DisplayName("命中子块 → 取父块整章（同 title 组仅单父块）")
    void assembleSingleParent() {
        KnowledgeChunk hit = hit(10L, 5L);
        KnowledgeChunk parent = parent(5L, "## 退款规则", "父块整章上下文内容");
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(parent);
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null)).thenReturn(List.of(parent));

        List<KnowledgeChunk> result = service.assemble(List.of(hit));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("父块整章上下文内容");
    }

    @Test
    @DisplayName("同 title 相邻父块组（长章节拆块）→ 整组按序拼接")
    void assembleContiguousGroup() {
        KnowledgeChunk hit = hit(10L, 5L);
        KnowledgeChunk p1 = parent(4L, "## 退款规则", "第一节内容");
        KnowledgeChunk p2 = parent(5L, "## 退款规则", "第二节内容");
        KnowledgeChunk p3 = parent(6L, "## 退款规则", "第三节内容");
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(p2);
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null))
                .thenReturn(List.of(p1, p2, p3));

        List<KnowledgeChunk> result = service.assemble(List.of(hit));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent())
                .isEqualTo("第一节内容\n\n第二节内容\n\n第三节内容");
    }

    @Test
    @DisplayName("同 title 但 id 不连续（跨文档同标题）→ 只装命中父块不混拼")
    void assembleNonContiguousGroup() {
        KnowledgeChunk hit = hit(10L, 5L);
        KnowledgeChunk p1 = parent(4L, "## 退款规则", "相邻节内容");
        KnowledgeChunk p2 = parent(5L, "## 退款规则", "命中父块内容");
        KnowledgeChunk pFar = parent(100L, "## 退款规则", "另一文档同标题内容");
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(p2);
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null))
                .thenReturn(List.of(p1, p2, pFar));

        List<KnowledgeChunk> result = service.assemble(List.of(hit));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("相邻节内容\n\n命中父块内容");
    }

    @Test
    @DisplayName("多个命中指向同一父块 → 去重只装配一次")
    void assembleDedupByParent() {
        KnowledgeChunk hit1 = hit(10L, 5L);
        KnowledgeChunk hit2 = hit(11L, 5L);
        KnowledgeChunk parent = parent(5L, "## 退款规则", "父块整章上下文内容");
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(parent);
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null)).thenReturn(List.of(parent));

        List<KnowledgeChunk> result = service.assemble(List.of(hit1, hit2));

        assertThat(result).hasSize(1);
    }

    @Test
    @DisplayName("预算截断：多命中装配总长不超过配置预算")
    void assembleBudgetCut() {
        KnowledgeChunk hit1 = hit(10L, 5L);
        KnowledgeChunk hit2 = hit(20L, 6L);
        KnowledgeChunk parent1 = parent(5L, "## 标题A", "A".repeat(800));
        KnowledgeChunk parent2 = parent(6L, "## 标题B", "B".repeat(800));
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(parent1);
        when(knowledgeChunkMapper.selectParentById(6L, null)).thenReturn(parent2);
        when(knowledgeChunkMapper.selectParentsByTitle("## 标题A", null)).thenReturn(List.of(parent1));
        when(knowledgeChunkMapper.selectParentsByTitle("## 标题B", null)).thenReturn(List.of(parent2));

        List<KnowledgeChunk> result = service.assemble(List.of(hit1, hit2));

        int total = result.stream().mapToInt(c -> c.getContent().length()).sum();
        assertThat(total).isLessThanOrEqualTo(1000);
        assertThat(result).hasSize(2);
        assertThat(result.get(0).getContent()).hasSize(800);
        assertThat(result.get(1).getContent()).hasSize(200);
    }

    @Test
    @DisplayName("父块查询失败 → fail-open 降级子块原文")
    void assembleDegradeOnFailure() {
        KnowledgeChunk hit = hit(10L, 5L);
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenThrow(new RuntimeException("db down"));

        List<KnowledgeChunk> result = service.assemble(List.of(hit));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("命中子块原文10");
    }

    @Test
    @DisplayName("父块已删（parentId 查无此块）→ 降级子块原文")
    void assembleParentMissing() {
        KnowledgeChunk hit = hit(10L, 5L);
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(null);

        List<KnowledgeChunk> result = service.assemble(List.of(hit));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("命中子块原文10");
    }
}
