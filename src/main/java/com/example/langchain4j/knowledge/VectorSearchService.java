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
 * 混合检索服务：稠密语义（BGE-M3 + pgvector 余弦）与稀疏词权重（jieba 分词 + hashing → sparsevec 内积）
 * 双通道召回，RRF 融合排序。
 * <p>
 * 单通道可独立降级：稠密通道嵌入/DB 故障、稀疏通道分词/DB 故障均返回空列表（fail-open），
 * 双通道均空才整体降级为空（上游按无结果处理）。
 */
@Slf4j
@Service
public class VectorSearchService {

    private static final int DEFAULT_TOP_K = 3;
    /**
     * 稠密通道最小相似度阈值（1 - 余弦距离，越大越相似）：低于该值视为不相关直接丢弃。
     * 0.85 是 BGE-M3 下的严格档位，优先保证客服问答精度；若评测发现召回不足，
     * 应基于评测集下调（如 0.6~0.7），不要盲调。
     */
    private static final double DEFAULT_MIN_SCORE = 0.85;
    /** 稀疏通道最小内积阈值（已 L2 归一化即余弦）：低于该值不进融合池；稀疏余弦天然低于稠密，0.05 只滤零重叠噪声 */
    private static final double SPARSE_MIN_SCORE = 0.05;
    /** RRF 融合常数：经典取值 60，rank 从 1 起，决定低排名项对融合分的贡献衰减速度 */
    private static final int RRF_K = 60;
    /** 单路候选池大小：先宽召回再融合截断，避免单路 topK 截断丢掉融合后可排前的项 */
    private static final int FUSION_POOL = 10;

    private final KnowledgeChunkMapper knowledgeChunkMapper;
    private final EmbeddingService embeddingService;
    private final SparseEmbeddingService sparseEmbeddingService;
    private final AgentProperties properties;

    /** 注入知识块查询、稠密/稀疏嵌入服务与 Agent 配置。 */
    public VectorSearchService(KnowledgeChunkMapper knowledgeChunkMapper, EmbeddingService embeddingService,
                               SparseEmbeddingService sparseEmbeddingService, AgentProperties properties) {
        this.knowledgeChunkMapper = knowledgeChunkMapper;
        this.embeddingService = embeddingService;
        this.sparseEmbeddingService = sparseEmbeddingService;
        this.properties = properties;
    }

    /** 使用默认可见范围检索指定条数的知识块。 */
    public List<KnowledgeChunk> search(String query, int topK) {
        return search(query, topK, false);
    }

    /**
     * 混合检索相关知识块：稠密 + 稀疏双通道召回后 RRF 融合，返回 topK。
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
        long start = System.currentTimeMillis();
        String userId = UserContext.get();
        int k = Math.max(1, topK);
        // 步骤1：双通道独立宽召回（各取候选池大小，给 RRF 融合留出排序余地）
        List<KnowledgeChunk> dense = denseSearch(query, userId, privateOnly);
        List<KnowledgeChunk> sparse = sparseSearch(query, userId, privateOnly);
        // 步骤2：RRF 融合去重排序并截断返回
        List<KnowledgeChunk> fused = fuse(dense, sparse, k);
        log.info("[vector] search done, queryLength={}, topK={}, privateOnly={}, dense={}, sparse={}, fused={}, costMs={}",
                query.length(), k, privateOnly, dense.size(), sparse.size(), fused.size(),
                System.currentTimeMillis() - start);
        return fused;
    }

    /** 使用默认条数和默认可见范围检索知识块。 */
    public List<KnowledgeChunk> search(String query) {
        return search(query, DEFAULT_TOP_K);
    }

    /** 稠密通道：BGE-M3 嵌入 + pgvector 余弦检索；嵌入/DB 故障降级为空列表（fail-open，不拖累稀疏通道） */
    private List<KnowledgeChunk> denseSearch(String query, String userId, boolean privateOnly) {
        try {
            float[] queryVector = embeddingService.embed(query);
            return knowledgeChunkMapper.searchSimilar(queryVector, FUSION_POOL, DEFAULT_MIN_SCORE, userId, privateOnly);
        } catch (Exception e) {
            log.warn("[vector] dense search degraded, queryLength={}, reason={}", query.length(), e.getMessage());
            return List.of();
        }
    }

    /** 稀疏通道：查询分词 → sparsevec 文本 → pgvector 内积检索（专有名词/型号精确命中）；分词/DB 故障降级空列表（fail-open） */
    private List<KnowledgeChunk> sparseSearch(String query, String userId, boolean privateOnly) {
        try {
            String querySparse = sparseEmbeddingService.embed(query);
            if (querySparse == null) {
                // 查询无有效词（纯标点/停用词）：本通道无召回信号，交给稠密通道
                return List.of();
            }
            return knowledgeChunkMapper.searchSparse(querySparse, FUSION_POOL, SPARSE_MIN_SCORE, userId, privateOnly);
        } catch (Exception e) {
            log.warn("[vector] sparse search degraded, queryLength={}, reason={}", query.length(), e.getMessage());
            return List.of();
        }
    }

    /**
     * RRF 融合：score = Σ 1/(k + rank)，rank 从 1 起，两路分数叠加后降序取 topK。
     * 单路为空时退化为另一路的 topK 截断（不融合，行为等价单通道检索）。
     */
    private List<KnowledgeChunk> fuse(List<KnowledgeChunk> dense, List<KnowledgeChunk> sparse, int topK) {
        if (dense.isEmpty() && sparse.isEmpty()) {
            return List.of();
        }
        if (dense.isEmpty()) {
            return sparse.size() > topK ? sparse.subList(0, topK) : sparse;
        }
        if (sparse.isEmpty()) {
            return dense.size() > topK ? dense.subList(0, topK) : dense;
        }
        Map<Long, Double> scores = new LinkedHashMap<>();
        Map<Long, KnowledgeChunk> byId = new LinkedHashMap<>();
        accumulate(scores, byId, dense);
        accumulate(scores, byId, sparse);
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

    /** 使用默认条数和默认可见范围检索并组装上下文。 */
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
     *   <li>多个命中指向同一逻辑章节 → 只装配一次（同文档同版本同 title 去重）；</li>
     *   <li>单组受 parent-context-budget 限额，总数受 max-parents 限额，总长受 context-budget 限额。</li>
     * </ul>
     * 父块查询失败降级为子块原文（fail-open，不阻断主流程）。
     */
    public List<KnowledgeChunk> assemble(List<KnowledgeChunk> hits) {
        if (hits == null || hits.isEmpty()) {
            return List.of();
        }
        String userId = UserContext.get();
        int budget = Math.max(1, properties.getRetrieval().getContextBudget());
        int maxParents = Math.max(1, properties.getRetrieval().getMaxParents());
        int perParentBudget = properties.getRetrieval().getParentContextBudget();
        List<KnowledgeChunk> assembled = new ArrayList<>();
        Set<String> seenGroups = new HashSet<>();
        int used = 0;
        for (KnowledgeChunk hit : hits) {
            if (hit == null || hit.getContent() == null) {
                continue;
            }
            if (assembled.size() >= maxParents) {
                break;
            }
            int remaining = budget - used;
            if (remaining <= 0) {
                break;
            }
            // 单个父块组单独限额：防止第一个大章节吃掉全部预算，后续主题无上下文可用
            int cap = perParentBudget > 0 ? Math.min(remaining, perParentBudget) : remaining;
            ParentContext context = resolveParentContext(hit, userId, cap);
            if (context == null || context.text() == null || context.text().isBlank()) {
                continue;
            }
            // 逻辑章节去重：长章节拆成多个同 title 父块时，只装配一次，避免重复内容挤压其他主题
            if (!seenGroups.add(context.key())) {
                continue;
            }
            used += context.text().length();
            KnowledgeChunk ctx = new KnowledgeChunk();
            ctx.setContent(context.text());
            assembled.add(ctx);
        }
        return assembled;
    }

    /** 解析命中块的装配原文：新数据取父块（同 title 相邻组按命中块优先拼接），查询失败 fail-open 降级子块原文 */
    private ParentContext resolveParentContext(KnowledgeChunk hit, String userId, int budget) {
        if (hit.getParentId() == null) {
            return new ParentContext("c:" + hit.getId(), truncate(hit.getContent(), budget));
        }
        try {
            KnowledgeChunk parent = knowledgeChunkMapper.selectParentById(hit.getParentId(), userId);
            if (parent == null || parent.getContent() == null) {
                return new ParentContext("p:" + hit.getParentId(), truncate(hit.getContent(), budget));
            }
            String sectionKey = sectionKey(parent);
            if (parent.getTitle() == null || parent.getTitle().isBlank()) {
                return new ParentContext(sectionKey, truncate(parent.getContent(), budget));
            }
            // 同文档 + 同版本 + 同 title 父块组：长章节拆多父块时整组找回；id 相邻连续段再防重复章节误拼
            List<KnowledgeChunk> group = knowledgeChunkMapper.selectParentsByTitle(
                    parent.getTitle(), userId, parent.getDocumentId(), parent.getVersion());
            List<KnowledgeChunk> contiguous = contiguousGroup(group, parent.getId());
            if (contiguous.size() <= 1) {
                return new ParentContext(sectionKey, truncate(parent.getContent(), budget));
            }
            return new ParentContext(sectionKey, assembleAdjacentGroup(contiguous, parent.getId(), budget));
        } catch (Exception e) {
            log.warn("[vector] parent assembly degraded, parentId={}, reason={}", hit.getParentId(), e.getMessage());
            return new ParentContext("p:" + hit.getParentId(), truncate(hit.getContent(), budget));
        }
    }

    /** 逻辑章节去重键：同文档同版本的多个父块片段归为同一组；旧数据无 documentId 时退回父块 ID */
    private String sectionKey(KnowledgeChunk parent) {
        if (parent.getDocumentId() != null && parent.getTitle() != null) {
            return "s:" + parent.getDocumentId() + ":" + parent.getVersion() + ":" + parent.getTitle();
        }
        return "p:" + parent.getId();
    }

    /**
     * 同 title 相邻父块组装配：命中父块先完整入预算，再按距离向左右补相邻块，最后按 id 还原文档顺序。
     * 相邻块放不下时停止该方向，避免跳过中间块导致上下文断裂。
     */
    private String assembleAdjacentGroup(List<KnowledgeChunk> group, Long hitParentId, int budget) {
        int hitIndex = -1;
        for (int i = 0; i < group.size(); i++) {
            if (group.get(i) != null && hitParentId.equals(group.get(i).getId())) {
                hitIndex = i;
                break;
            }
        }
        if (hitIndex < 0) {
            return "";
        }
        String hitContent = group.get(hitIndex).getContent();
        if (hitContent == null) {
            return "";
        }
        if (hitContent.length() >= budget) {
            return hitContent.substring(0, budget);
        }
        int used = hitContent.length();
        int left = hitIndex - 1;
        int right = hitIndex + 1;
        boolean canLeft = true;
        boolean canRight = true;
        while (canLeft || canRight) {
            boolean progressed = false;
            if (canLeft) {
                if (left < 0) {
                    canLeft = false;
                } else {
                    String content = group.get(left).getContent();
                    if (content != null && used + 2 + content.length() <= budget) {
                        used += 2 + content.length();
                        left--;
                        progressed = true;
                    } else {
                        canLeft = false;
                    }
                }
            }
            if (canRight) {
                if (right >= group.size()) {
                    canRight = false;
                } else {
                    String content = group.get(right).getContent();
                    if (content != null && used + 2 + content.length() <= budget) {
                        used += 2 + content.length();
                        right++;
                        progressed = true;
                    } else {
                        canRight = false;
                    }
                }
            }
            if (!progressed) {
                break;
            }
        }
        StringBuilder sb = new StringBuilder();
        for (int i = left + 1; i < right; i++) {
            String content = group.get(i).getContent();
            if (content == null) {
                continue;
            }
            if (!sb.isEmpty()) {
                sb.append("\n\n");
            }
            sb.append(content);
        }
        return sb.toString();
    }

    /** 按剩余预算截断原文；null 视为空串 */
    private String truncate(String text, int budget) {
        if (text == null) {
            return "";
        }
        return text.length() <= budget ? text : text.substring(0, budget);
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

    /** 父块装配结果：key 为逻辑章节去重键，text 为实际放入上下文的文本 */
    private record ParentContext(String key, String text) {
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
