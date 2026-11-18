package com.example.langchain4j.knowledge;

import com.huaban.analysis.jieba.JiebaSegmenter;
import com.huaban.analysis.jieba.SegToken;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 稀疏向量服务：jieba 分词 → log-TF 词权重 → hashing trick 映射到固定维度 → L2 归一化，
 * 输出 pgvector sparsevec 文本格式（{idx:weight,...}/DIM，索引 1-based）。
 * <p>
 * 与稠密通道（BGE-M3 语义向量）互补：本通道负责"词精确命中"（专有名词/型号/缩写），
 * 稠密通道负责语义泛化，两路 RRF 融合。
 * <p>
 * 不使用 BGE-M3 原生 sparse 输出：Ollama /api/embed 只返回稠密向量，sparse 需要额外推理服务
 * （FlagEmbedding/TEI）；"分词 + 统计权重"是同一形态（维度=词、值=权重）的轻量落地。
 * 未引入 IDF 全局统计：需维护 term 文档频次表（双写+并发更新），当前知识库规模下 log-TF 足够区分。
 */
@Slf4j
@Component
public class SparseEmbeddingService {

    /** hashing 槽位数（2^20）：单块词数几百的量级下碰撞率极低，且碰撞只造成轻微权重串扰 */
    private static final int DIMENSION = 1 << 20;
    /** 单块非零元素上限：sparsevec 支持 16000，这里只保留权重最高的前 N 个词（防超长块词表膨胀） */
    private static final int MAX_NON_ZERO = 512;
    /** 停用词：高频虚词，命中无区分度，进向量只会抬高所有块的噪声底 */
    private static final Set<String> STOP_WORDS = Set.of(
            "的", "了", "是", "在", "和", "与", "或", "有", "也", "就", "都", "而", "及", "着", "被", "把", "让",
            "对", "从", "到", "为", "以", "这", "那", "其", "之", "等", "但", "并", "呢", "吧", "啊", "吗", "我", "你", "他", "它");

    /** jieba 分词器（字典只读、内部无会话状态，可多线程共用；首次分词会加载内置词典） */
    private final JiebaSegmenter segmenter = new JiebaSegmenter();

    /**
     * 启动预热：首次调用 jieba 会加载内置词典（本机约 2 秒），提前在启动阶段完成，
     * 避免首个检索请求把“模型加载 + 词典加载”叠加后超过工具超时。
     */
    @PostConstruct
    void warmUp() {
        long start = System.currentTimeMillis();
        try {
            segmenter.process("知识库检索预热", JiebaSegmenter.SegMode.SEARCH);
            log.info("[sparse-embedding] warm-up done, costMs={}", System.currentTimeMillis() - start);
        } catch (Exception e) {
            log.warn("[sparse-embedding] warm-up failed, first query may be slower, reason={}",
                    e.getMessage());
        }
    }

    /**
     * 文本 → sparsevec 文本（如 {12:0.6800,530:0.4200}/1048576）。
     * 无有效词返回 null：写入端存 NULL，检索端按 sparse_vector IS NOT NULL 跳过该块（RRF 退化为稠密单路）。
     */
    public String embed(String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        Map<String, Integer> termFreq = new HashMap<>();
        for (SegToken token : segmenter.process(text, JiebaSegmenter.SegMode.SEARCH)) {
            String word = token.word == null ? "" : token.word.trim().toLowerCase(Locale.ROOT);
            if (!indexable(word)) {
                continue;
            }
            termFreq.merge(word, 1, Integer::sum);
        }
        if (termFreq.isEmpty()) {
            return null;
        }
        // 词 → hash 槽位权重；1+ln(tf) 抑制高频词主导，同槽位（hash 碰撞）权重累加
        Map<Integer, Double> slots = new HashMap<>();
        for (Map.Entry<String, Integer> entry : termFreq.entrySet()) {
            int index = Math.floorMod(entry.getKey().hashCode(), DIMENSION) + 1;
            slots.merge(index, 1.0 + Math.log(entry.getValue()), Double::sum);
        }
        // L2 归一化：内积即余弦相似度，避免长块天然比短块分高
        double norm = Math.sqrt(slots.values().stream().mapToDouble(w -> w * w).sum());
        if (norm <= 0) {
            return null;
        }
        List<Map.Entry<Integer, Double>> sorted = new ArrayList<>(slots.entrySet());
        if (sorted.size() > MAX_NON_ZERO) {
            // 超上限时保留权重最高的前 N 个（先按权重降序截取，再按槽位升序输出）
            sorted.sort(Map.Entry.<Integer, Double>comparingByValue().reversed());
            sorted = new ArrayList<>(sorted.subList(0, MAX_NON_ZERO));
        }
        // 按槽位升序输出：同一文本产出稳定字符串，便于调试对比与索引复用
        sorted.sort(Map.Entry.comparingByKey());
        StringBuilder sb = new StringBuilder("{");
        for (int i = 0; i < sorted.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sorted.get(i).getKey()).append(':').append(format(sorted.get(i).getValue() / norm));
        }
        return sb.append("}/").append(DIMENSION).toString();
    }

    /** 可索引词：非空、非停用词、至少含一个字母/数字/汉字（过滤纯标点碎片） */
    private boolean indexable(String word) {
        if (word.isEmpty() || STOP_WORDS.contains(word)) {
            return false;
        }
        return word.codePoints().anyMatch(Character::isLetterOrDigit);
    }

    /** 权重格式：4 位小数（sparsevec 文本解析精度足够，同时压缩存储与传输体积） */
    private String format(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }
}
