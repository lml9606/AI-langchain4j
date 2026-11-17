package com.example.langchain4j.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * 稀疏向量服务单测：输出格式契约（sparsevec 文本）、无词场景 null 语义、
 * L2 归一化与输出稳定性是写入/检索两端正确性的前提，必须覆盖。
 */
class SparseEmbeddingServiceTest {

    private static final int DIMENSION = 1 << 20;

    private final SparseEmbeddingService service = new SparseEmbeddingService();

    /** 解析 {idx:weight,...}/DIM 为槽位→权重表 */
    private Map<Integer, Double> parse(String sparsevec) {
        Map<Integer, Double> slots = new HashMap<>();
        String body = sparsevec.substring(1, sparsevec.indexOf('}'));
        for (String pair : body.split(",")) {
            String[] kv = pair.split(":");
            slots.put(Integer.parseInt(kv[0]), Double.parseDouble(kv[1]));
        }
        return slots;
    }

    @Test
    @DisplayName("中文文本 → sparsevec 文本格式（1-based 索引 + 维度后缀）")
    void embedChineseText() {
        String result = service.embed("如何申请退款？退款一般需要三到五个工作日到账");

        assertThat(result).isNotNull();
        assertThat(result).startsWith("{").endsWith("}/" + DIMENSION);
        Map<Integer, Double> slots = parse(result);
        assertThat(slots).isNotEmpty();
        // 槽位 1-based：下限 1、上限 DIMENSION
        assertThat(slots.keySet()).allMatch(i -> i >= 1 && i <= DIMENSION);
    }

    @Test
    @DisplayName("L2 归一化：权重平方和 ≈ 1（内积即余弦）")
    void embedL2Normalized() {
        Map<Integer, Double> slots = parse(service.embed("退货政策七天无理由退换运费险"));

        double sumSquares = slots.values().stream().mapToDouble(w -> w * w).sum();
        assertThat(sumSquares).isCloseTo(1.0, within(0.01));
    }

    @Test
    @DisplayName("同文本两次输出完全一致（hashing 确定性，保证索引/调试可复现）")
    void embedDeterministic() {
        String text = "发票开具流程与开票信息修改";

        assertThat(service.embed(text)).isEqualTo(service.embed(text));
    }

    @Test
    @DisplayName("无有效词（纯标点/纯停用词/空白）→ null（该块不进稀疏索引，RRF 退化稠密单路）")
    void embedNoIndexableWord() {
        assertThat(service.embed(null)).isNull();
        assertThat(service.embed("   ")).isNull();
        assertThat(service.embed("？？？！！！。。。")).isNull();
        assertThat(service.embed("的 了 是 在 和 与")).isNull();
    }

    @Test
    @DisplayName("词频加权：高频词权重高于低频词（log-TF 抑制但保留区分度）")
    void embedTermFrequencyWeighting() {
        // "退款" 出现 3 次，"发票" 出现 1 次：同长度词频高者权重应更高
        Map<Integer, Double> slots = parse(service.embed("退款 退款 退款 发票"));

        double max = slots.values().stream().mapToDouble(Double::doubleValue).max().orElse(0);
        double min = slots.values().stream().mapToDouble(Double::doubleValue).min().orElse(0);
        assertThat(max).isGreaterThan(min);
    }
}
