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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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
    private SparseEmbeddingService sparseEmbeddingService;
    @Mock
    private AgentProperties properties;

    private VectorSearchService service;

    @BeforeEach
    void setUp() {
        AgentProperties.Retrieval retrieval = new AgentProperties.Retrieval();
        retrieval.setContextBudget(1000);
        // lenient：空命中用例不读预算配置，避免严格模式报不必要的 stub
        lenient().when(properties.getRetrieval()).thenReturn(retrieval);
        service = new VectorSearchService(knowledgeChunkMapper, embeddingService, sparseEmbeddingService, properties);
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
    @DisplayName("显式上下文预算 → 覆盖默认 budget，供复合问题按子查询调用")
    void assembleWithExplicitBudget() {
        KnowledgeChunk orphan = new KnowledgeChunk();
        orphan.setId(1L);
        orphan.setContent("预算".repeat(200));

        List<KnowledgeChunk> result = service.assemble(List.of(orphan), 120);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).hasSize(120);
    }

    @Test
    @DisplayName("命中子块 → 取父块整章（同 title 组仅单父块）")
    void assembleSingleParent() {
        KnowledgeChunk hit = hit(10L, 5L);
        KnowledgeChunk parent = parent(5L, "## 退款规则", "父块整章上下文内容");
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(parent);
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null, null, null))
                .thenReturn(List.of(parent));

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
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null, null, null))
                .thenReturn(List.of(p1, p2, p3));

        List<KnowledgeChunk> result = service.assemble(List.of(hit));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent())
                .isEqualTo("第一节内容\n\n第二节内容\n\n第三节内容");
    }

    @Test
    @DisplayName("长同标题组：命中末尾父块时优先保留命中块，不被组开头挤掉")
    void assembleKeepsHitParentWhenBudgetTight() {
        KnowledgeChunk hit = hit(10L, 7L);
        KnowledgeChunk p1 = parent(5L, "## 长章节", "A".repeat(400));
        KnowledgeChunk p2 = parent(6L, "## 长章节", "B".repeat(400));
        KnowledgeChunk p3 = parent(7L, "## 长章节", "C".repeat(400));
        when(knowledgeChunkMapper.selectParentById(7L, null)).thenReturn(p3);
        when(knowledgeChunkMapper.selectParentsByTitle("## 长章节", null, null, null))
                .thenReturn(List.of(p1, p2, p3));

        List<KnowledgeChunk> result = service.assemble(List.of(hit));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).contains("C".repeat(400));
        assertThat(result.get(0).getContent()).doesNotContain("A".repeat(400));
        assertThat(result.get(0).getContent().length()).isLessThanOrEqualTo(1000);
    }

    @Test
    @DisplayName("同 title 但 id 不连续（跨文档同标题）→ 只装命中父块不混拼")
    void assembleNonContiguousGroup() {
        KnowledgeChunk hit = hit(10L, 5L);
        KnowledgeChunk p1 = parent(4L, "## 退款规则", "相邻节内容");
        KnowledgeChunk p2 = parent(5L, "## 退款规则", "命中父块内容");
        KnowledgeChunk pFar = parent(100L, "## 退款规则", "另一文档同标题内容");
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(p2);
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null, null, null))
                .thenReturn(List.of(p1, p2, pFar));

        List<KnowledgeChunk> result = service.assemble(List.of(hit));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("相邻节内容\n\n命中父块内容");
    }

    @Test
    @DisplayName("父块组回查带 documentId/version：不同版本文档不混拼")
    void assembleScopesParentsByDocumentVersion() {
        KnowledgeChunk hit = hit(10L, 5L);
        KnowledgeChunk parent = parent(5L, "## 退款规则", "版本二内容");
        parent.setDocumentId("doc-1");
        parent.setVersion(2);
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(parent);
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null, "doc-1", 2))
                .thenReturn(List.of(parent));

        List<KnowledgeChunk> result = service.assemble(List.of(hit));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("版本二内容");
        verify(knowledgeChunkMapper).selectParentsByTitle("## 退款规则", null, "doc-1", 2);
    }

    @Test
    @DisplayName("多个命中指向同一父块 → 去重只装配一次")
    void assembleDedupByParent() {
        KnowledgeChunk hit1 = hit(10L, 5L);
        KnowledgeChunk hit2 = hit(11L, 5L);
        KnowledgeChunk parent = parent(5L, "## 退款规则", "父块整章上下文内容");
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(parent);
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null, null, null))
                .thenReturn(List.of(parent));

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
        when(knowledgeChunkMapper.selectParentsByTitle("## 标题A", null, null, null))
                .thenReturn(List.of(parent1));
        when(knowledgeChunkMapper.selectParentsByTitle("## 标题B", null, null, null))
                .thenReturn(List.of(parent2));

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

    @Test
    @DisplayName("maxParents 限制：只装配前 N 个父块组，避免单个主题挤占全部上下文")
    void assembleLimitsParentGroups() {
        AgentProperties.Retrieval retrieval = properties.getRetrieval();
        retrieval.setContextBudget(10000);
        retrieval.setMaxParents(2);
        retrieval.setParentContextBudget(2000);

        KnowledgeChunk hit1 = hit(10L, 5L);
        KnowledgeChunk hit2 = hit(11L, 6L);
        KnowledgeChunk hit3 = hit(12L, 7L);
        KnowledgeChunk parent1 = parent(5L, "## 标题A", "A".repeat(20));
        KnowledgeChunk parent2 = parent(6L, "## 标题B", "B".repeat(20));
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(parent1);
        when(knowledgeChunkMapper.selectParentById(6L, null)).thenReturn(parent2);

        List<KnowledgeChunk> result = service.assemble(List.of(hit1, hit2, hit3));

        assertThat(result).hasSize(2);
        verify(knowledgeChunkMapper).selectParentById(5L, null);
        verify(knowledgeChunkMapper).selectParentById(6L, null);
        verify(knowledgeChunkMapper, never()).selectParentById(7L, null);
    }

    @Test
    @DisplayName("parentContextBudget：每个父块组单独限额，多个主题均能保留")
    void assembleCapsEachParentGroup() {
        AgentProperties.Retrieval retrieval = properties.getRetrieval();
        retrieval.setContextBudget(10000);
        retrieval.setMaxParents(3);
        retrieval.setParentContextBudget(300);

        KnowledgeChunk hit1 = hit(10L, 5L);
        KnowledgeChunk hit2 = hit(11L, 6L);
        KnowledgeChunk hit3 = hit(12L, 7L);
        KnowledgeChunk parent1 = parent(5L, "## 标题A", "A".repeat(500));
        KnowledgeChunk parent2 = parent(6L, "## 标题B", "B".repeat(500));
        KnowledgeChunk parent3 = parent(7L, "## 标题C", "C".repeat(500));
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(parent1);
        when(knowledgeChunkMapper.selectParentById(6L, null)).thenReturn(parent2);
        when(knowledgeChunkMapper.selectParentById(7L, null)).thenReturn(parent3);

        List<KnowledgeChunk> result = service.assemble(List.of(hit1, hit2, hit3));

        assertThat(result).hasSize(3);
        assertThat(result).allMatch(chunk -> chunk.getContent().length() == 300);
    }

    @Test
    @DisplayName("同一逻辑章节的多个父块片段 → 只装配一次，不重复占用预算")
    void assembleDedupsSameLogicalSection() {
        KnowledgeChunk hit1 = hit(10L, 5L);
        KnowledgeChunk hit2 = hit(11L, 6L);
        KnowledgeChunk parent1 = parent(5L, "## 退款规则", "片段一");
        KnowledgeChunk parent2 = parent(6L, "## 退款规则", "片段二");
        parent1.setDocumentId("doc-1");
        parent1.setVersion(2);
        parent2.setDocumentId("doc-1");
        parent2.setVersion(2);
        when(knowledgeChunkMapper.selectParentById(5L, null)).thenReturn(parent1);
        when(knowledgeChunkMapper.selectParentById(6L, null)).thenReturn(parent2);
        when(knowledgeChunkMapper.selectParentsByTitle("## 退款规则", null, "doc-1", 2))
                .thenReturn(List.of(parent1, parent2));

        List<KnowledgeChunk> result = service.assemble(List.of(hit1, hit2));

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getContent()).isEqualTo("片段一\n\n片段二");
    }
}
