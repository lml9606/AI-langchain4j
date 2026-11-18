package com.example.langchain4j.service;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.util.TokenEstimator;
import com.example.langchain4j.knowledge.EmbeddingService;
import com.example.langchain4j.knowledge.KnowledgeFileParser;
import com.example.langchain4j.knowledge.SparseEmbeddingService;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.repository.entity.KnowledgeChunk;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    private SparseEmbeddingService sparseEmbeddingService;
    @Mock
    private VectorSearchService vectorSearchService;
    @Mock
    private KnowledgeFileParser fileParser;
    @Mock
    private AgentProperties properties;
    @Mock
    private ThreadPoolTaskExecutor importExecutor;

    private KnowledgeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeService(knowledgeChunkMapper, embeddingService, sparseEmbeddingService,
                vectorSearchService, fileParser, properties, importExecutor);
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
        assertThat(sections.get(0).title()).isEqualTo("1.1 退款条件");
        assertThat(sections.get(0).body()).contains("正文A段落");
        assertThat(sections.get(1).title()).isEqualTo("1.2 退款流程");
        assertThat(sections.get(1).body()).contains("正文C段落");
    }

    @Test
    @DisplayName("父子分块：纯容器大标题不单独成块，各子标题按完整路径成为父块")
    void splitParentsKeepsContainerHeadingWithSubheadings() {
        String content = "## 一、Java 基础\n\n### 1. HashMap 底层原理\n\n正文A。\n\n"
                + "### 2. String、StringBuilder、StringBuffer\n\n正文B。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("一、Java 基础 > 1. HashMap 底层原理");
        assertThat(sections.get(0).body()).startsWith("### 1. HashMap 底层原理").contains("正文A")
                .doesNotContain("### 2. String、StringBuilder、StringBuffer", "正文B");
        assertThat(sections.get(1).title()).isEqualTo("一、Java 基础 > 2. String、StringBuilder、StringBuffer");
        assertThat(sections.get(1).body()).startsWith("### 2. String、StringBuilder、StringBuffer").contains("正文B");
    }

    @Test
    @DisplayName("父子分块：唯一一级标题视为文档标题，子标题路径不重复包含文档标题")
    void splitParentsTreatsSingleH1AsDocumentTitle() {
        String content = "# Java 后端面试题\n\n## 一、Java 基础\n\n"
                + "### 1. HashMap 底层原理\n\n正文A。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("一、Java 基础 > 1. HashMap 底层原理");
        assertThat(sections.get(0).body()).startsWith("### 1. HashMap 底层原理").contains("正文A")
                .doesNotContain("# Java 后端面试题", "## 一、Java 基础");
    }

    @Test
    @DisplayName("父子分块：容器标题自己有导语时，导语单独成块，子标题继续各自成块")
    void splitParentsKeepsContainerOwnBody() {
        String content = "## 一、Java 基础\n\n本章导语。\n\n### 1. HashMap 底层原理\n\n正文A。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("一、Java 基础");
        assertThat(sections.get(0).body()).contains("本章导语").doesNotContain("正文A");
        assertThat(sections.get(1).title()).isEqualTo("一、Java 基础 > 1. HashMap 底层原理");
        assertThat(sections.get(1).body()).contains("正文A");
    }

    @Test
    @DisplayName("父子分块：自适应选中 H3 时，H4 及更深内容并入所属 H3 父块")
    void splitParentsKeepsDeeperHeadingsInSelectedParent() {
        String content = "## A\n\n### B\n\n#### C\n\n" + "正文C。".repeat(200)
                + "\n\n#### D\n\n" + "正文D。".repeat(200)
                + "\n\n### E\n\n" + "正文E。".repeat(200);

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("A > B");
        assertThat(sections.get(0).body()).startsWith("### B")
                .contains("#### C", "正文C", "#### D", "正文D");
        assertThat(sections.get(1).title()).isEqualTo("A > E");
    }

    @Test
    @DisplayName("自适应选层：H2 小节长度合适时选 H2，不强行下钻到 H3")
    void splitParentsChoosesLevelTwoWhenSectionsFit() {
        String content = "## A\n\n" + "A内容。".repeat(200) + "\n\n### A1\n\n" + "A1。".repeat(100)
                + "\n\n## B\n\n" + "B内容。".repeat(200) + "\n\n### B1\n\n" + "B1。".repeat(100);

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("A");
        assertThat(sections.get(0).body()).contains("### A1", "A1。");
        assertThat(sections.get(1).title()).isEqualTo("B");
        assertThat(sections.get(1).body()).contains("### B1", "B1。");
    }

    @Test
    @DisplayName("自适应选层：H2 过大、H3 小节合适时选 H3")
    void splitParentsChoosesLevelThreeWhenH2TooLarge() {
        String content = "## A\n\n### A1\n\n" + "A1。".repeat(300)
                + "\n\n### A2\n\n" + "A2。".repeat(300);

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("A > A1");
        assertThat(sections.get(1).title()).isEqualTo("A > A2");
    }

    @Test
    @DisplayName("父子分块：长父块按软标题续切，追问与答案不被拦腰截断")
    void splitParentsLongSectionUsesSoftLabels() {
        String content = "### Q\n\n**标准答案**\n\n" + "标准答案内容。".repeat(50)
                + "\n\n**追问 1：问题一？**\n\n" + "追问一内容。".repeat(50)
                + "\n\n**追问 2：问题二？**\n\n" + "追问二内容。".repeat(50);

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 600);

        assertThat(sections.size()).isGreaterThan(1);
        assertThat(sections).allMatch(section -> section.title().equals("Q"));
        assertThat(sections).anyMatch(section -> section.body().contains("**标准答案**"));
        assertThat(sections).anyMatch(section -> section.body().startsWith("**追问 1"));
        assertThat(sections).anyMatch(section -> section.body().startsWith("**追问 2"));
    }

    @Test
    @DisplayName("父子分块：围栏代码块里的 # 注释不当作标题")
    void splitParentsIgnoresHeadingInFencedCode() {
        String content = "## A\n\n```bash\n# 注释不是标题\necho hi\n```\n\n正文A。\n\n## B\n\n正文B。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("A");
        assertThat(sections.get(0).body()).contains("# 注释不是标题", "正文A");
        assertThat(sections.get(1).title()).isEqualTo("B");
    }

    @Test
    @DisplayName("父子分块：无强结构标题时多级编号（1.1）识别为标题")
    void splitParentsByNumberedTitleLines() {
        String content = "1.1 退款条件\n正文A。\n1.2 退款流程\n正文B。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("1.1 退款条件");
        assertThat(sections.get(1).title()).isEqualTo("1.2 退款流程");
    }

    @Test
    @DisplayName("父子分块：存在强结构标题时，有序列表（1. xxx）不得误判为章节")
    void splitParentsIgnoresOrderedListUnderMarkdownTitles() {
        String content = "# 退款流程\n1. 打开订单页面\n2. 点击申请退款按钮\n3. 等待客服审核";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("退款流程");
        assertThat(sections.get(0).body()).contains("2. 点击申请退款按钮");
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
        assertThat(sections).allMatch(s -> s.title().equals("退款规则"));
    }

    @Test
    @DisplayName("父子分块：首个标题前的正文（前言）用兜底标题单独成节")
    void splitParentsPreambleBeforeFirstTitle() {
        String content = "文档前言\n这是导语段落。\n## 1.1 正文标题\n正文章节内容。";

        List<KnowledgeService.ParentSection> sections = service.splitParents(content, 2000);

        assertThat(sections).hasSize(2);
        assertThat(sections.get(0).title()).isEqualTo("文档前言");
        assertThat(sections.get(0).body()).contains("导语段落");
        assertThat(sections.get(1).title()).isEqualTo("1.1 正文标题");
    }

    @Test
    @DisplayName("语义切分：段落保持完整，不按字符窗口拦腰截断")
    void semanticSplitKeepsParagraphs() {
        String paragraphA = "第一段说明退款条件。".repeat(3);
        String paragraphB = "第二段说明退款流程。".repeat(3);
        String content = paragraphA + "\n\n" + paragraphB;

        List<String> pieces = service.splitSemantic(content, 35, 0, 120);

        assertThat(pieces).contains(paragraphA, paragraphB);
    }

    @Test
    @DisplayName("语义切分：围栏代码块跨空行保持完整")
    void semanticSplitKeepsCodeBlock() {
        String code = "```java\nint a = 1;\n\nint b = 2;\n```";
        String content = "代码示例说明。\n\n" + code + "\n\n代码结束说明。";

        List<String> pieces = service.splitSemantic(content, 40, 0, 120);

        assertThat(pieces).anyMatch(piece -> piece.contains(code));
    }

    @Test
    @DisplayName("语义切分：整行加粗标签与答案绑定，追问问题不会单独成块")
    void semanticSplitKeepsQuestionWithAnswer() {
        String content = "**标准答案**\n\n标准答案内容。\n\n**面试官追问**\n\n"
                + "**追问 1：初始容量为什么是 16？**\n\n答案一。\n\n"
                + "**追问 2：扩容怎么重新定位？**\n\n答案二。";

        List<String> pieces = service.splitSemantic(content, 80, 0, 200);

        assertThat(pieces).anyMatch(piece -> piece.contains("追问 1") && piece.contains("答案一"));
        assertThat(pieces).anyMatch(piece -> piece.contains("追问 2") && piece.contains("答案二"));
        assertThat(pieces).noneMatch(piece -> piece.trim().equals("**追问 1：初始容量为什么是 16？**"));
        assertThat(pieces).noneMatch(piece -> piece.trim().equals("**追问 2：扩容怎么重新定位？**"));
    }

    @Test
    @DisplayName("语义切分：字符与 token 双预算均不超限")
    void semanticSplitRespectsTokenBudget() {
        String content = "这是一段很长的中文内容，用来验证 token 预算。".repeat(100);

        List<String> pieces = service.splitSemantic(content, 1000, 20, 120);

        assertThat(pieces).isNotEmpty();
        assertThat(pieces).allMatch(piece -> piece.length() <= 120);
        assertThat(pieces).allMatch(piece -> TokenEstimator.estimate(piece) <= 120);
    }

    @Test
    @DisplayName("语义重叠只取完整句子：下一块不从半个句子开始")
    void semanticOverlapStartsAtWholeSentence() {
        String first = "第一句完整。第二句也完整。第三句完整。";
        String second = "下一段内容。";

        List<String> pieces = service.splitSemantic(first + "\n\n" + second, 22, 14, 200);

        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(1)).startsWith("第三句完整。");
        assertThat(pieces.get(1)).contains(second);
    }

    @Test
    @DisplayName("语义重叠预算内没有完整句子时放弃重叠，不截半句")
    void semanticOverlapSkipsWhenNoWholeSentence() {
        String longSentence = "甲".repeat(34) + "。";
        String second = "乙".repeat(5) + "。";

        List<String> pieces = service.splitSemantic(longSentence + "\n\n" + second, 40, 5, 200);

        assertThat(pieces).hasSize(2);
        assertThat(pieces.get(1)).isEqualTo(second);
    }

    @Test
    @DisplayName("文档导入：父子块写入同一 documentId/version，并在成功后清理旧版本")
    void importDocumentWritesDocumentVersion() {
        AgentProperties.Knowledge knowledge = new AgentProperties.Knowledge();
        when(properties.getKnowledge()).thenReturn(knowledge);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(sparseEmbeddingService.embed(anyString())).thenReturn(null);
        when(knowledgeChunkMapper.insert(any(KnowledgeChunk.class))).thenAnswer(invocation -> {
            KnowledgeChunk chunk = invocation.getArgument(0);
            chunk.setId(100L);
            return 1;
        });

        UserContext.set("u1", "USER");
        try {
            int chunks = service.importDocument("## 退款规则\n正文说明。", "{}", null, null, "doc-1", 2);
            assertThat(chunks).isEqualTo(2);
        } finally {
            UserContext.clear();
        }

        ArgumentCaptor<KnowledgeChunk> parentCaptor = ArgumentCaptor.forClass(KnowledgeChunk.class);
        verify(knowledgeChunkMapper).insert(parentCaptor.capture());
        assertThat(parentCaptor.getValue().getDocumentId()).isEqualTo("doc-1");
        assertThat(parentCaptor.getValue().getVersion()).isEqualTo(2);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<KnowledgeChunk>> childrenCaptor = ArgumentCaptor.forClass(List.class);
        verify(knowledgeChunkMapper).insertBatch(childrenCaptor.capture());
        assertThat(childrenCaptor.getValue()).isNotEmpty();
        assertThat(childrenCaptor.getValue()).allSatisfy(child -> {
            assertThat(child.getDocumentId()).isEqualTo("doc-1");
            assertThat(child.getVersion()).isEqualTo(2);
            assertThat(child.getParentId()).isEqualTo(100L);
        });
        verify(knowledgeChunkMapper).deleteOlderVersions("doc-1", 2, "u1");
    }

    @Test
    @DisplayName("文档导入失败：清理已写入的半成品版本，避免遮住旧版本")
    void importDocumentCleansPartialVersionOnFailure() {
        AgentProperties.Knowledge knowledge = new AgentProperties.Knowledge();
        when(properties.getKnowledge()).thenReturn(knowledge);
        when(embeddingService.embed(anyString())).thenReturn(new float[]{0.1f, 0.2f});
        when(sparseEmbeddingService.embed(anyString())).thenReturn(null);
        when(knowledgeChunkMapper.insert(any(KnowledgeChunk.class))).thenAnswer(invocation -> {
            KnowledgeChunk chunk = invocation.getArgument(0);
            chunk.setId(200L);
            return 1;
        });
        when(knowledgeChunkMapper.insertBatch(any())).thenThrow(new RuntimeException("db down"));

        UserContext.set("u2", "USER");
        try {
            assertThatThrownBy(() -> service.importDocument("## 标题\n正文。", "{}", null, null, "doc-2", 1))
                    .isInstanceOf(RuntimeException.class)
                    .hasMessageContaining("db down");
        } finally {
            UserContext.clear();
        }

        verify(knowledgeChunkMapper).deleteDocumentVersion("doc-2", 1, "u2");
    }

    @Test
    @DisplayName("自适应选层：面试题文档 L2 过大、L3 约 1.7k 时选 L3")
    void splitParentsChoosesLevelThreeForInterviewDocStructure() {
        StringBuilder content = new StringBuilder("# 面试题\n\n## 一、Java 基础\n\n");
        for (int i = 1; i <= 5; i++) {
            content.append("### ").append(i).append(". 问题").append(i).append("\n\n")
                    .append("答案内容。".repeat(340)).append("\n\n");
        }
        content.append("## 二、并发\n\n");
        for (int i = 6; i <= 9; i++) {
            content.append("### ").append(i).append(". 问题").append(i).append("\n\n")
                    .append("答案内容。".repeat(340)).append("\n\n");
        }

        List<KnowledgeService.ParentSection> sections = service.splitParents(content.toString(), 2000);

        assertThat(sections).hasSize(9);
        assertThat(sections.get(0).title()).isEqualTo("一、Java 基础 > 1. 问题1");
        assertThat(sections).allMatch(section -> section.body().length() <= 2000);
    }
}
