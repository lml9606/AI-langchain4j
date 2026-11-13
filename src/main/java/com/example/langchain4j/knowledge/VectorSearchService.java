package com.example.langchain4j.knowledge;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.KnowledgeChunk;
import com.example.langchain4j.repository.mapper.KnowledgeChunkMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
    private static final double DEFAULT_MIN_SCORE = 0.85;
    /** 词面相似度下限：滤掉零重叠的纯噪声命中（中文短查询三元组弱匹配），低于 0.05 不进融合池 */
    private static final double LEXICAL_MIN_SCORE = 0.05;
    /** RRF 融合常数：经典取值 60，rank 从 1 起，决定低排名项对融合分的贡献衰减速度 */
    private static final int RRF_K = 60;
    /** 单路候选池大小：先宽召回再融合截断，避免单路 topK 截断丢掉融合后可排前的项 */
    private static final int FUSION_POOL = 10;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingService embeddingService;
    private final AgentProperties properties;

    public VectorSearchService(KnowledgeChunkMapper knowledgeChunkMapper, EmbeddingService embeddingService,
                               AgentProperties properties) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.embeddingService = embeddingService;
        this.properties = properties;
    }

    public List<KnowledgeChunk> search(String query, int topK) {
        return search(query, topK, false);
    }

    /**
     * 混合检索相关知识块：稠密 + 词面双通道召回后 RRF 融合，返回 topK。
     * <p>
     * 用户隔离：当前身份取自 {@link UserContext}（HTTP 线程直查或图执行线程透传）；
     * 无身份上下文（null）时仅可见公共库（user_id IS NULL），安全默认宁缺勿滥。
     *
     * @param query       查询文本
     * @param topK        返回条数
     * @param privateOnly 仅检索本人知识（visibility=private），不再含公共库
     * @return 相关块列表；双通道均失败时返回空列表（fail-open，由调用方降级）
     */
    public List<KnowledgeChunk> search(String query, int topK, boolean privateOnly) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        String userId = UserContext.get();
        int k = Math.max(1, topK);
        // 步骤1：双通道独立宽召回（各取候选池大小，给 RRF 融合留出排序余地）
        List<KnowledgeChunk> dense = denseSearch(query, userId, privateOnly);
        List<KnowledgeChunk> lexical = lexicalSearch(query, userId, privateOnly);
        // 步骤2：RRF 融合去重排序并截断返回
        return fuse(dense, lexical, k);
    }

    public List<KnowledgeChunk> search(String query) {
        return search(query, DEFAULT_TOP_K);
    }

    /** 稠密通道：BGE-M3 嵌入 + pgvector 余弦检索；嵌入/DB 故障降级为空列表（fail-open，不拖累词面通道） */
    private List<KnowledgeChunk> denseSearch(String query, String userId, boolean privateOnly) {
        try {
            float[] queryVector = embeddingService.embed(query);
            return knowledgeChunkMapper.searchSimilar(queryVector, FUSION_POOL, DEFAULT_MIN_SCORE, userId, privateOnly);
        } catch (Exception e) {
            log.warn("[vector] dense search degraded, query={}, reason={}", query, e.getMessage());
            return List.of();
        }
    }

    /** 词面通道：pg_trgm 三元组相似度检索（中文无需分词）；扩展缺失/DB 故障降级为空列表（fail-open） */
    private List<KnowledgeChunk> lexicalSearch(String query, String userId, boolean privateOnly) {
        try {
            return knowledgeChunkMapper.searchLexical(query, FUSION_POOL, LEXICAL_MIN_SCORE, userId, privateOnly);
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

    /**
     * 检索并父子装配上下文：命中子块 → 按 parent_id 取父块整章（同 title 相邻组拼接补长章节漏），
     * 装配结果按预算截断后沿用【参考资料 N】标注拼接；无结果返回空串供 prompt 组装降级。
     *
     * @param topK 检索命中数（默认 {@link #DEFAULT_TOP_K}）
     */
    public String searchContext(String query, int topK) {
        return searchContext(query, topK, false);
    }

    public String searchContext(String query) {
        return searchContext(query, DEFAULT_TOP_K, false);
    }

    /**
     * 检索并父子装配上下文：命中子块 → 按 parent_id 取父块整章（同 title 相邻组拼接补长章节漏），
     * 装配结果按预算截断后沿用【参考资料 N】标注拼接；无结果返回空串供 prompt 组装降级。
     *
     * @param topK        检索命中数（默认 {@link #DEFAULT_TOP_K}）
     * @param privateOnly 仅检索本人知识（visibility=private）
     */
    public String searchContext(String query, int topK, boolean privateOnly) {
        List<KnowledgeChunk> hits = search(query, topK, privateOnly);
        if (hits.isEmpty()) {
            return "";
        }
        return buildContext(assemble(hits));
    }

    /**
     * 父子分块装配：检索命中子块后，按 parent_id 取父块整章上下文装配。
     * <ul>
     *   <li>新数据（parentId 非空）→ 取父块；父块 title 非空再取同 title 父块组按 id 序拼接
     *       （长章节被拆成多父块时整组找回）；</li>
     *   <li>孤儿子块（存量数据 parentId=null）→ 退化为子块原文；</li>
     *   <li>多个命中指向同一父块 → 只装配一次（最相关命中优先占预算）；</li>
     *   <li>总长按 agent.retrieval.context-budget 截断（单组超预算即截尾）。</li>
     * </ul>
     * 父块查询失败降级为子块原文（fail-open，不阻断主流程）。
     */
    public List<KnowledgeChunk> assemble(List<KnowledgeChunk> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        String userId = UserContext.get();
        int budget = Math.max(1, properties.getRetrieval().getContextBudget());
        List<KnowledgeChunk> assembled = new ArrayList<>();
        Set<String> seenGroups = new HashSet<>();
        int used = 0;
        for (KnowledgeChunk hit : hits) {
            if (hit == null || hit.getContent() == null) {
                continue;
            }
            // 去重键：有 parentId 按父块组去重，孤儿子块按自身 ID 去重
            String dedupKey = hit.getParentId() != null ? "p:" + hit.getParentId() : "c:" + hit.getId();
            if (!seenGroups.add(dedupKey)) {
                continue;
            }
            String part = resolveParentContext(hit, userId);
            if (part.isBlank()) {
                continue;
            }
            // 预算截断：剩余预算不足时截尾（首命中优先保证完整，尾部命中可能只入部分）
            int remaining = budget - used;
            if (remaining <= 0) {
                break;
            }
            if (part.length() > remaining) {
                part = part.substring(0, remaining);
            }
            used += part.length();
            KnowledgeChunk ctx = new KnowledgeChunk();
            ctx.setContent(part);
            assembled.add(ctx);
        }
        return assembled;
    }

    /** 解析命中块的装配原文：新数据取父块（同 title 相邻组拼接），孤儿子块退化子块原文；查询失败 fail-open 降级子块原文 */
    private String resolveParentContext(KnowledgeChunk hit, String userId) {
        if (hit.getParentId() == null) {
            return hit.getContent();
        }
        try {
            KnowledgeChunk parent = knowledgeChunkMapper.selectParentById(hit.getParentId(), userId);
            if (parent == null || parent.getContent() == null) {
                return hit.getContent();
            }
            if (parent.getTitle() == null || parent.getTitle().isBlank()) {
                return parent.getContent();
            }
            // 同 title 父块组：长章节拆多父块时整组找回；仅取 id 相邻连续段（同文档拆块 id 连续），防跨文档同标题误拼
            List<KnowledgeChunk> group = knowledgeChunkMapper.selectParentsByTitle(parent.getTitle(), userId);
            List<KnowledgeChunk> contiguous = contiguousGroup(group, parent.getId());
            if (contiguous.size() <= 1) {
                return parent.getContent();
            }
            return contiguous.stream()
                    .map(KnowledgeChunk::getContent)
                    .filter(java.util.Objects::nonNull)
                    .reduce((a, b) -> a + "\n\n" + b)
                    .orElse(parent.getContent());
        } catch (Exception e) {
            log.warn("[vector] parent assembly degraded, parentId={}, reason={}", hit.getParentId(), e.getMessage());
            return hit.getContent();
        }
    }

    /**
     * 取包含目标 id 的相邻连续段：长章节拆块 id 连续，断开的同 title 块（跨文档/重复章节）不混拼。
     * 无目标返回空列表（调用方退化单父块）。
     */
    private List<KnowledgeChunk> contiguousGroup(List<KnowledgeChunk> group, Long targetId) {
        if (group == null || group.isEmpty()) {
            return List.of();
        }
        int index = -1;
        for (int i = 0; i < group.size(); i++) {
            if (group.get(i) != null && targetId.equals(group.get(i).getId())) {
                index = i;
                break;
            }
        }
        if (index < 0) {
            return List.of();
        }
        int left = index;
        while (left > 0 && adjacent(group.get(left - 1), group.get(left))) {
            left--;
        }
        int right = index;
        while (right < group.size() - 1 && adjacent(group.get(right), group.get(right + 1))) {
            right++;
        }
        return new ArrayList<>(group.subList(left, right + 1));
    }

    /** 相邻判定：id 差 1 且均非空（同文档连续插入的父块） */
    private boolean adjacent(KnowledgeChunk a, KnowledgeChunk b) {
        return a != null && b != null && a.getId() != null && b.getId() != null
                && a.getId() + 1 == b.getId();
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
