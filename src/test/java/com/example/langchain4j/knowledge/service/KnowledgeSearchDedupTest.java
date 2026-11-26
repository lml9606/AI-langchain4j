package com.example.langchain4j.knowledge.service;

import com.example.langchain4j.knowledge.entity.KnowledgeChunk;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 检索结果去重测评：确保重复内容不占用 API 的 topK 名额。 */
class KnowledgeSearchDedupTest {

    /** 完全相同或仅空白不同的正文只保留第一次出现。 */
    @Test
    void duplicateContentsAreCollapsed() {
        KnowledgeChunk first = chunk(1, "HashMap 使用数组、链表和红黑树");
        KnowledgeChunk duplicate = chunk(2, "HashMap   使用数组、链表和红黑树");
        KnowledgeChunk other = chunk(3, "ConcurrentHashMap 使用 CAS 和 synchronized");

        List<KnowledgeChunk> result = KnowledgeService.distinctByContent(
                List.of(first, duplicate, other), 5);

        assertThat(result).extracting(KnowledgeChunk::getId).containsExactly(1L, 3L);
    }

    /** 空内容不进入结果，避免空证据占用名额。 */
    @Test
    void blankContentsAreSkipped() {
        List<KnowledgeChunk> result = KnowledgeService.distinctByContent(
                List.of(chunk(1, " "), chunk(2, "\n\t"), chunk(3, "有效内容")), 5);

        assertThat(result).extracting(KnowledgeChunk::getId).containsExactly(3L);
    }

    /** 去重后严格按请求上限截断，不把扩大的候选池原样返回。 */
    @Test
    void resultRespectsRequestedLimit() {
        List<KnowledgeChunk> result = KnowledgeService.distinctByContent(
                List.of(chunk(1, "A"), chunk(2, "B"), chunk(3, "C")), 2);

        assertThat(result).extracting(KnowledgeChunk::getId).containsExactly(1L, 2L);
    }

    /** 构造最小知识块；测试只关心正文和排序。 */
    private KnowledgeChunk chunk(long id, String content) {
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setContent(content);
        return chunk;
    }
}
