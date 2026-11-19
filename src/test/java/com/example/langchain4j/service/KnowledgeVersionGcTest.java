package com.example.langchain4j.service;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.KnowledgeDocument;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import com.example.langchain4j.repository.mapper.KnowledgeDocumentMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 旧版本回收单测：重点是"只回收非当前版本、按批循环、单文档失败不影响整体"。
 */
@ExtendWith(MockitoExtension.class)
class KnowledgeVersionGcTest {

    @Mock
    private KnowledgeDocumentMapper knowledgeDocumentMapper;
    @Mock
    private KnowledgeChunkMapper knowledgeChunkMapper;
    @Mock
    private AgentProperties properties;

    private AgentProperties.Knowledge config;
    private KnowledgeVersionGc gc;

    @BeforeEach
    void setUp() {
        config = new AgentProperties.Knowledge();
        when(properties.getKnowledge()).thenReturn(config);
        gc = new KnowledgeVersionGc(knowledgeDocumentMapper, knowledgeChunkMapper, properties);
    }

    @Test
    @DisplayName("GC 关闭：不扫描、不删除")
    void skipsWhenDisabled() {
        config.setGcEnabled(false);

        gc.purgeExpiredVersions();

        verify(knowledgeDocumentMapper, never()).selectPurgeCandidates(anyInt(), anyInt());
    }

    @Test
    @DisplayName("按批回收直到不足一批，并标记该文档已回收")
    void purgesInBatchesUntilDrained() {
        config.setGcBatchSize(500);
        KnowledgeDocument document = document("doc-1", "u1", 3);
        when(knowledgeDocumentMapper.selectPurgeCandidates(anyInt(), anyInt())).thenReturn(List.of(document));
        when(knowledgeChunkMapper.deleteInactiveVersions("doc-1", "u1", 3, 500, 24))
                .thenReturn(500, 120, 0);

        gc.purgeExpiredVersions();

        verify(knowledgeChunkMapper, org.mockito.Mockito.times(2))
                .deleteInactiveVersions("doc-1", "u1", 3, 500, 24);
        verify(knowledgeDocumentMapper).markPurged("doc-1", "u1");
    }

    @Test
    @DisplayName("单文档删除失败：不标记已回收，也不中断任务")
    void failureDoesNotMarkPurged() {
        KnowledgeDocument document = document("doc-2", "u2", 1);
        when(knowledgeDocumentMapper.selectPurgeCandidates(anyInt(), anyInt())).thenReturn(List.of(document));
        when(knowledgeChunkMapper.deleteInactiveVersions(eq("doc-2"), eq("u2"), eq(1), anyInt(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        gc.purgeExpiredVersions();

        verify(knowledgeDocumentMapper, never()).markPurged("doc-2", "u2");
    }

    private KnowledgeDocument document(String documentId, String userId, int activeVersion) {
        KnowledgeDocument document = new KnowledgeDocument();
        document.setDocumentId(documentId);
        document.setUserId(userId);
        document.setActiveVersion(activeVersion);
        return document;
    }
}
