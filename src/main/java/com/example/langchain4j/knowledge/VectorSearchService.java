package com.example.langchain4j.knowledge;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.repository.entity.KnowledgeChunk;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 混合检索服务：稠密语义（BGE-M3 + pgvector 余弦）与词面（pg_trgm 三元组）双通道召回，RRF 融合排序。
 * <p>
 * 单通道可独立降级：稠密通道嵌入/DB 故障、词面通道扩展缺失/DB 故障均返回空列表（fail-open），
 * 双通道均空才整体降级为空（上游按无结果处理）。
 */
@Slf4j
@Service
public class VectorSearchService {

    private static final int DEFAULT_TOP_K = 3;
    /** 最小相似度阈值：pgvector 余弦距离越接近 1 越相似，低于 0.3 视为不相关直接丢弃 */
    private static final double DEFAULT_MIN_SCORE = 0.3;
    /** 词面相似度下限：滤掉零重叠的纯噪声命中（中文短查询三元组弱匹配），低于 0.05 不进融合池 */
    private static final double LEXICAL_MIN_SCORE = 0.05;
    /** RRF 融合常数：经典取值 60，rank 从 1 起，决定低排名项对融合分的贡献衰减速度 */
    private static final int RRF_K = 60;
    /** 单路候选池大小：先宽召回再融合截断，避免单路 topK 截断丢掉融合后可排前的项 */
    private static final int FUSION_POOL = 10;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingService embeddingService;

    public VectorSearchService(KnowledgeChunkMapper knowledgeChunkMapper, EmbeddingService embeddingService) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.embeddingService = embeddingService;
    }

    /**
     * 混合检索相关知识块：稠密 + 词面双通道召回后 RRF 融合，返回 topK。
     * <p>
     * 用户隔离：当前身份取自 {@link UserContext}（HTTP 线程直查或图执行线程透传）；
     * 无身份上下文（null）时仅可见公共库（user_id IS NULL），安全默认宁缺勿滥。
     *
     * @param query 查询文本
     * @param topK  返回条数
     * @return 相关块列表；双通道均失败时返回空列表（fail-open，由调用方降级）
     */
    public List<KnowledgeChunk> search(String query, int topK) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String userId = UserContext.get();
        int k = Math.max(1, topK);
        // 步骤1：双通道独立宽召回（各取候选池大小，给 RRF 融合留出排序余地）
        List<KnowledgeChunk> dense = denseSearch(query, userId);
        List<KnowledgeChunk> lexical = lexicalSearch(query, userId);
        // 步骤2：RRF 融合去重排序并截断返回
        return fuse(dense, lexical, k);
    }

    public List<KnowledgeChunk> search(String query) {
        return search(query, DEFAULT_TOP_K);
    }

    /** 稠密通道：BGE-M3 嵌入 + pgvector 余弦检索；嵌入/DB 故障降级为空列表（fail-open，不拖累词面通道） */
    private List<KnowledgeChunk> denseSearch(String query, String userId) {
        try {
            float[] queryVector = embeddingService.embed(query);
            return knowledgeChunkMapper.searchSimilar(queryVector, FUSION_POOL, DEFAULT_MIN_SCORE, userId);
        } catch (Exception e) {
            log.warn("[vector] dense search degraded, query={}, reason={}", query, e.getMessage());
            return List.of();
        }
    }

    /** 词面通道：pg_trgm 三元组相似度检索（中文无需分词）；扩展缺失/DB 故障降级为空列表（fail-open） */
    private List<KnowledgeChunk> lexicalSearch(String query, String userId) {
        try {
            return knowledgeChunkMapper.searchLexical(query, FUSION_POOL, LEXICAL_MIN_SCORE, userId);
        } catch (Exception e) {
            log.warn("[vector] lexical search degraded, query={}, reason={}", query, e.getMessage());
            return List.of();
        }
    }

    /**
     * RRF 融合：score = Σ 1/(k + rank)，rank 从 1 起，两路分数叠加后降序取 topK。
     * 单路为空时退化为另一路的 topK 截断（不融合，行为等价旧版单通道检索）。
     */
    private List<KnowledgeChunk> fuse(List<KnowledgeChunk> dense, List<KnowledgeChunk> lexical, int topK) {
        if (dense.isEmpty() && lexical.isEmpty()) {
            return List.of();
        }
        if (dense.isEmpty()) {
            return lexical.size() > topK ? lexical.subList(0, topK) : lexical;
        }
        if (lexical.isEmpty()) {
            return dense.size() > topK ? dense.subList(0, topK) : dense;
        }
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, KnowledgeChunk> byId = new LinkedHashMap<>();
        accumulate(scores, byId, dense);
        accumulate(scores, byId, lexical);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> byId.get(e.getKey()))
                .toList();
    }

    /** 单路累加 RRF 分：rank 从 1 递增；两路同命中同一 id 时分数叠加（共同认可的信号被放大） */
    private void accumulate(Map<Long, Double> scores, Map<Long, KnowledgeChunk> byId, List<KnowledgeChunk> chunks) {
        int rank = 1;
        for (KnowledgeChunk chunk : chunks) {
            if (chunk == null || chunk.getId() == null) {
                continue;
            }
            scores.put(chunk.getId(), scores.getOrDefault(chunk.getId(), 0.0) + 1.0 / (RRF_K + rank));
            byId.putIfAbsent(chunk.getId(), chunk);
            rank++;
        }
    }

    /** 拼接检索上下文；无结果返回空串，供 prompt 组装降级 */
    public String buildContext(List<KnowledgeChunk> chunks) {
        if (chunks == null || chunks.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++) {
            KnowledgeChunk chunk = chunks.get(i);
            if (chunk == null || chunk.getContent() == null) {
                continue;
            }
            sb.append("【参考资料 ").append(i + 1).append("】")
              .append(chunk.getContent()).append('\n');
        }
        return sb.toString();
    }
}
