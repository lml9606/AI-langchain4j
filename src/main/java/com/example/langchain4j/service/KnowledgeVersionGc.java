package com.example.langchain4j.service;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.KnowledgeDocument;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import com.example.langchain4j.repository.mapper.KnowledgeDocumentMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 知识文档旧版本回收。
 *
 * <p>版本发布后旧版本仍保留在库里，用于回滚与内容对比；超过保留期后由本任务回收。
 * 回收与在线读写解耦：
 * <ul>
 *   <li>只删非 active_version 的行，删错也影响不到当前可读版本；</li>
 *   <li>按批删除，单批行数受限，避免长事务与大锁；</li>
 *   <li>删除失败只记录日志，下一轮继续，不阻塞导入与检索。</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KnowledgeVersionGc {

    private final KnowledgeDocumentMapper knowledgeDocumentMapper;
    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final AgentProperties properties;

    /**
     * 定时回收：固定间隔执行（默认 1 小时），启动后延迟 10 分钟再跑，避开应用启动高峰。
     */
    @Scheduled(fixedDelayString = "${agent.knowledge.gc-interval-ms:3600000}",
            initialDelayString = "${agent.knowledge.gc-initial-delay-ms:600000}")
    public void purgeExpiredVersions() {
        AgentProperties.Knowledge config = properties.getKnowledge();
        if (!config.isGcEnabled()) {
            return;
        }
        List<KnowledgeDocument> candidates;
        try {
            candidates = knowledgeDocumentMapper.selectPurgeCandidates(
                    config.getVersionRetentionHours(), config.getGcMaxDocuments());
        } catch (Exception e) {
            log.warn("[knowledge-gc] scan failed: {}", e.getMessage());
            return;
        }
        int removed = 0;
        int purgedDocuments = 0;
        for (KnowledgeDocument document : candidates) {
            try {
                int rows = purgeDocument(document, config.getGcBatchSize(), config.getVersionRetentionHours());
                // 即使没有可删行也标记：否则无旧版本的文档每轮都会被重复扫描
                knowledgeDocumentMapper.markPurged(document.getDocumentId(), document.getUserId());
                if (rows > 0) {
                    removed += rows;
                    purgedDocuments++;
                }
            } catch (Exception e) {
                // 单个文档失败不影响其他文档，下一轮重试
                log.warn("[knowledge-gc] purge failed, documentId={}, version={}, reason={}",
                        document.getDocumentId(), document.getActiveVersion(), e.getMessage());
            }
        }
        if (removed > 0) {
            log.info("[knowledge-gc] purge done, documents={}, rows={}", purgedDocuments, removed);
        }
    }

    /** 分批删除某文档的非当前版本，返回删除总行数 */
    private int purgeDocument(KnowledgeDocument document, int batchSize, int retentionHours) {
        int boundedBatch = Math.max(1, batchSize);
        int total = 0;
        int removed;
        do {
            removed = knowledgeChunkMapper.deleteInactiveVersions(
                    document.getDocumentId(), document.getUserId(),
                    document.getActiveVersion(), boundedBatch, retentionHours);
            total += removed;
        } while (removed == boundedBatch);
        return total;
    }
}
