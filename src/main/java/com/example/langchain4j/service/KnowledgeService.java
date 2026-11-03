package com.example.langchain4j.service;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.KnowledgeBatchImportRequest;
import com.example.langchain4j.dto.KnowledgeBatchImportResponse;
import com.example.langchain4j.dto.KnowledgeHealthResponse;
import com.example.langchain4j.dto.KnowledgeImportRequest;
import com.example.langchain4j.dto.KnowledgeSearchResponse;
import com.example.langchain4j.dto.KnowledgeUpdateRequest;
import com.example.langchain4j.knowledge.EmbeddingService;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.repository.entity.KnowledgeChunk;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 知识服务：导入（嵌入 + 落库）、检索与知识治理（批量导入/更新删除/分块可配/健康度统计）。
 */
@Slf4j
@Service
public class KnowledgeService {

    private static final int DEFAULT_TOP_K = 3;
    private static final int MAX_TOP_K = 10;
    /** 分块大小防御边界：过小碎片化、过大单块语义稀释 */
    private static final int MIN_CHUNK_SIZE = 200;
    private static final int MAX_CHUNK_SIZE = 5000;
    /** 分块边界字符：优先在这些位置断（句末语义完整，避免拦腰截断） */
    private static final String BOUNDARY_CHARS = "。！？；\n";

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingService embeddingService;
    private final VectorSearchService vectorSearchService;
    private final AgentProperties properties;

    public KnowledgeService(KnowledgeChunkMapper knowledgeChunkMapper,
                            EmbeddingService embeddingService,
                            VectorSearchService vectorSearchService,
                            AgentProperties properties) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.embeddingService = embeddingService;
        this.vectorSearchService = vectorSearchService;
        this.properties = properties;
    }

    /**
     * 导入单条知识：文本嵌入后写入 pgvector。
     * 用户隔离：知识块归属当前登录用户（他人检索不可见；公共库为 user_id IS NULL，由运营侧导入）。
     *
     * @return 新知识块 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public long importChunk(KnowledgeImportRequest request) {
        String content = sanitize(request.getContent());
        float[] embedding = embeddingService.embed(content);
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setContent(content);
        chunk.setEmbedding(embedding);
        chunk.setMetadata(request.getMetadata());
        chunk.setUserId(UserContext.require());
        knowledgeChunkMapper.insert(chunk);
        log.info("[knowledge] chunk imported, id={}, length={}", chunk.getId(), content.length());
        return chunk.getId();
    }

    /** 语义检索（topK 有界防御），检索失败降级为空列表由上游处理 */
    public KnowledgeSearchResponse search(String query, int topK) {
        int bounded = Math.min(Math.max(topK, 1), MAX_TOP_K);
        List<KnowledgeChunk> chunks = vectorSearchService.search(query, bounded);
        List<KnowledgeSearchResponse.Chunk> result = chunks.stream()
                .map(c -> new KnowledgeSearchResponse.Chunk(c.getContent(), c.getMetadata()))
                .toList();
        return KnowledgeSearchResponse.of(result);
    }

    public KnowledgeSearchResponse search(String query) {
        return search(query, DEFAULT_TOP_K);
    }

    /**
     * 导入长文档：按可配分块参数切分 → 逐块嵌入 → 批量落库（单条 INSERT 多 VALUES，天然原子）。
     * 分块可配：请求级 chunkSize/chunkOverlap 优先，缺省用 agent.knowledge 配置值。
     *
     * @return 落库块数
     */
    public int importDocument(String content, String metadata, Integer chunkSize, Integer chunkOverlap) {
        String cleaned = sanitize(content);
        if (cleaned.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "content 清洗后为空");
        }
        int size = boundedChunkSize(chunkSize);
        int overlap = boundedChunkOverlap(chunkOverlap, size);
        List<String> pieces = split(cleaned, size, overlap);
        String userId = UserContext.require();
        List<KnowledgeChunk> chunks = new ArrayList<>(pieces.size());
        for (int i = 0; i < pieces.size(); i++) {
            KnowledgeChunk chunk = new KnowledgeChunk();
            chunk.setContent(pieces.get(i));
            chunk.setEmbedding(embeddingService.embed(pieces.get(i)));
            chunk.setMetadata(withChunkInfo(metadata, i + 1, pieces.size()));
            chunk.setUserId(userId);
            chunks.add(chunk);
        }
        if (chunks.size() == 1) {
            knowledgeChunkMapper.insert(chunks.get(0));
        } else {
            knowledgeChunkMapper.insertBatch(chunks);
        }
        log.info("[knowledge] document imported, pieces={}, chunkSize={}, overlap={}",
                chunks.size(), size, overlap);
        return chunks.size();
    }

    /**
     * 批量导入：逐篇独立处理，单篇失败不中断整批（返回逐条结果供重试失败项）。
     * 文档数上限按配置校验（防一次请求打爆嵌入服务）。
     */
    public KnowledgeBatchImportResponse batchImport(KnowledgeBatchImportRequest request) {
        int maxDocs = Math.max(1, properties.getKnowledge().getBatchMaxDocuments());
        List<KnowledgeBatchImportRequest.Item> items = request.items();
        if (items.size() > maxDocs) {
            throw new BizException(ErrorCode.BAD_REQUEST, "单次最多导入 " + maxDocs + " 篇文档");
        }
        List<KnowledgeBatchImportResponse.ItemResult> results = new ArrayList<>(items.size());
        int imported = 0;
        int failed = 0;
        for (int i = 0; i < items.size(); i++) {
            KnowledgeBatchImportRequest.Item item = items.get(i);
            try {
                int chunks = importDocument(item.content(), item.metadata(), item.chunkSize(), item.chunkOverlap());
                results.add(new KnowledgeBatchImportResponse.ItemResult(i, chunks, true, null));
                imported++;
            } catch (Exception e) {
                log.warn("[knowledge] batch import item {} failed: {}", i, e.getMessage());
                results.add(new KnowledgeBatchImportResponse.ItemResult(i, 0, false, e.getMessage()));
                failed++;
            }
        }
        return new KnowledgeBatchImportResponse(items.size(), imported, failed, results);
    }

    /**
     * 更新知识块：归属校验（仅本人私有块可改，公共库块 403）→ 重嵌 → 更新。
     * SQL 层再按 user_id 过滤（双保险，防 TOCTOU 越权）。
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateChunk(long id, KnowledgeUpdateRequest request) {
        String userId = UserContext.require();
        KnowledgeChunk existing = knowledgeChunkMapper.selectById(id, userId);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或不可见");
        }
        if (existing.getUserId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "公共库知识块仅运营侧可维护");
        }
        String content = sanitize(request.getContent());
        if (content.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "content 清洗后为空");
        }
        KnowledgeChunk chunk = new KnowledgeChunk();
        chunk.setId(id);
        chunk.setContent(content);
        chunk.setEmbedding(embeddingService.embed(content));
        chunk.setMetadata(request.getMetadata());
        chunk.setUserId(userId);
        if (knowledgeChunkMapper.updateContent(chunk) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或已被删除");
        }
        log.info("[knowledge] chunk updated, id={}", id);
    }

    /** 删除知识块：仅本人私有块可删（公共库块 403），SQL 层 user_id 过滤双保险 */
    public void deleteChunk(long id) {
        String userId = UserContext.require();
        KnowledgeChunk existing = knowledgeChunkMapper.selectById(id, userId);
        if (existing == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "知识块不存在或不可见");
        }
        if (existing.getUserId() == null) {
            throw new BizException(ErrorCode.FORBIDDEN, "公共库知识块仅运营侧可维护");
        }
        knowledgeChunkMapper.deleteById(id, userId);
        log.info("[knowledge] chunk deleted, id={}", id);
    }

    /** 知识库健康度：当前用户可见范围（公共库 + 本人私有库）聚合统计 */
    public KnowledgeHealthResponse health() {
        return KnowledgeHealthResponse.from(knowledgeChunkMapper.healthStats(UserContext.require()));
    }

    /**
     * 长文档切分：优先在句末标点断（回退窗口内找最近边界，避免拦腰截断），
     * 无边界则硬切；chunkOverlap 保留跨块边界的语义连续。
     */
    List<String> split(String content, int chunkSize, int chunkOverlap) {
        if (content.length() <= chunkSize) {
            return List.of(content);
        }
        List<String> pieces = new ArrayList<>();
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            if (end < content.length()) {
                // 回退窗口 [start + chunkSize/2, end)：找最近句末标点，避免把句子拦腰截断
                int boundary = lastBoundary(content, start + chunkSize / 2, end);
                if (boundary > 0) {
                    end = boundary + 1;
                }
            }
            String piece = content.substring(start, end).trim();
            if (!piece.isEmpty()) {
                pieces.add(piece);
            }
            if (end >= content.length()) {
                break;
            }
            // 下一块起点回退 overlap；至少前进 1，防边界计算异常导致死循环
            start = Math.max(end - chunkOverlap, start + 1);
        }
        return pieces;
    }

    /** 在 [from, to) 范围内找最后一个句末标点位置；无则返回 -1 */
    private int lastBoundary(String content, int from, int to) {
        for (int i = to - 1; i >= from; i--) {
            if (BOUNDARY_CHARS.indexOf(content.charAt(i)) >= 0) {
                return i;
            }
        }
        return -1;
    }

    /** 分块大小防御边界：200~5000（过小碎片化、过大单块语义稀释） */
    private int boundedChunkSize(Integer requestChunkSize) {
        int configured = properties.getKnowledge().getChunkSize();
        int size = requestChunkSize != null ? requestChunkSize : configured;
        return Math.min(Math.max(size, MIN_CHUNK_SIZE), MAX_CHUNK_SIZE);
    }

    /** 分块重叠防御边界：0 ~ chunkSize/2（负值归零，过大反而稀释块语义） */
    private int boundedChunkOverlap(Integer requestOverlap, int chunkSize) {
        int configured = properties.getKnowledge().getChunkOverlap();
        int overlap = requestOverlap != null ? requestOverlap : configured;
        return Math.min(Math.max(overlap, 0), chunkSize / 2);
    }

    /** 元数据追加分块信息（chunkIndex/chunkCount）：原 JSON 对象合并，非 JSON 原文保留在 raw 字段 */
    private String withChunkInfo(String metadata, int index, int total) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (metadata != null && !metadata.isBlank()) {
            try {
                Map<String, Object> parsed = JsonUtil.fromJson(metadata, new TypeReference<>() {
                });
                if (parsed != null) {
                    map.putAll(parsed);
                }
            } catch (Exception e) {
                map.put("raw", metadata);
            }
        }
        map.put("chunkIndex", index);
        map.put("chunkCount", total);
        return JsonUtil.toJson(map);
    }

    /** 控制字符清洗 + 边界空白清理（嵌入前的防御性处理） */
    private String sanitize(String content) {
        return content.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
