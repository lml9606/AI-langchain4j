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
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

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
    @Mock
    private ThreadPoolTaskExecutor importExecutor;

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(knowledgeChunkMapper, embeddingService, vectorSearchService,
                properties, importExecutor);
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

    @Test
    @DisplayName("父子分块：标题行开新节，节首行即标题")
    void splitParentsByTitleLines() {
        String content = "## 1.1 退款条件\n正文A段落。\n\n正文B段落。\n## 1.2 退款流程\n正文C段落。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("## 1.1 退款条件");
        assertThat(sections.get(0).body()).contains("正文A段落");
        assertThat(sections.get(1).title()).isEqualTo("## 1.2 退款流程");
        assertThat(sections.get(1).body()).contains("正文C段落");
    }

    @Test
    @DisplayName("父子分块：数字编号与中文章节行同样识别为标题")
    void splitParentsByNumberedTitleLines() {
        String content = "1.1 退款条件\n正文A。\n第2章 退款流程\n正文B。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("1.1 退款条件");
        assertThat(sections.get(1).title()).isEqualTo("第2章 退款流程");
    }

    @Test
    @DisplayName("父子分块：无标题行时单一节，首行作兜底标题")
    void splitParentsWithoutTitle() {
        String content = "退款政策说明\n这是正文第一段。\n\n这是正文第二段。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("退款政策说明");
        assertThat(sections.get(0).body()).isEqualTo(content);
    }

    @Test
    @DisplayName("父子分块：长章节超父块大小续切为同 title 多父块")
    void splitParentsLongSectionSameTitle() {
        String content = "## 退款规则\n" + "很长的正文。".repeat(300);

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 500);

        assertThat(sections.size()).isGreaterThan(1);
        assertThat(sections).allMatch(s -> s.title().equals("## 退款规则"));
    }

    @Test
    @DisplayName("父子分块：首个标题前的正文（前言）用兜底标题单独成节")
    void splitParentsPreambleBeforeFirstTitle() {
        String content = "文档前言\n这是导语段落。\n## 1.1 正文标题\n正文章节内容。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("文档前言");
        assertThat(sections.get(0).body()).contains("导语段落");
        assertThat(sections.get(1).title()).isEqualTo("## 1.1 正文标题");
    }
}
