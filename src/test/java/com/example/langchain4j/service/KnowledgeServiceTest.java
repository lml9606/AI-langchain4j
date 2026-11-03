package com.example.langchain4j.service;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.knowledge.EmbeddingService;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 知识服务分块算法单测：分块是知识治理的核心能力，
 * 边界切分/重叠衔接/死循环防护/空块过滤必须覆盖。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeServiceTest {

    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private EmbeddingService embeddingService;
    @Mock
    private VectorSearchService vectorSearchService;
    @Mock
    private AgentProperties properties;

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(knowledgeChunkMapper, embeddingService, vectorSearchService, properties);
    }

    @Test
    @DisplayName("短文本不超过分块大小 → 不切分")
    void noSplitForShortText() {
        List<String> pieces = service.split("简短内容", 800, 80);
        assertThat(pieces).containsExactly("简短内容");
    }

    @Test
    @DisplayName("长文本优先在句末标点断块，避免句子拦腰截断")
    void splitAtSentenceBoundary() {
        List<String> pieces = service.split("第一句。第二句！第三句？", 5, 0);

        assertThat(pieces).hasSize(3);
        assertThat(pieces.get(0)).contains("第一句");
        assertThat(pieces.get(1)).contains("第二句");
        assertThat(pieces.get(2)).isEqualTo("第三句？");
    }

    @Test
    @DisplayName("无标点长文本硬切，不产生空块与死循环")
    void hardSplitWithoutBoundary() {
        List<String> pieces = service.split("ABCDEFGHIJKLMNOPQRST", 4, 0);

        assertThat(pieces).containsExactly("ABCD", "EFGH", "IJKL", "MNOP", "QRST");
    }

    @Test
    @DisplayName("chunkOverlap 生效：相邻块按重叠量衔接，保留跨块语义")
    void overlapBetweenChunks() {
        List<String> pieces = service.split("ABCDEFGH", 4, 2);

        assertThat(pieces).containsExactly("ABCD", "CDEF", "EFGH");
    }

    @Test
    @DisplayName("trim 后为空的片段被跳过，不产生空块")
    void skipBlankPieces() {
        List<String> pieces = service.split("AAAA \n\n BBBB", 4, 0);

        assertThat(pieces).allMatch(p -> !p.isEmpty());
    }
}
