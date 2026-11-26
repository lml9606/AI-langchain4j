package com.example.langchain4j.common.context;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;

/**
 * 单个调用场景的预算档位。
 *
 * 只有三个旋钮：总预算、各块权重、各块保底。算法骨架固定，参数可变，
 * 这样换模型只改 totalTokens、想让检索多占就改权重、不想让记忆被饿死就抬保底，
 * 不需要为每个场景写一套 if-else。
 *
 * @param totalTokens 本次调用可用的输入 token 上限（已扣除输出与余量）
 * @param weights     剩余预算的分配权重，未配置按 1 处理
 * @param minimums    各块的保底 token 数，未配置按 0 处理
 */
public record ContextProfile(int totalTokens,
                             Map<BlockType, Integer> weights,
                             Map<BlockType, Integer> minimums) {

    /** 归一化：总预算至少 1，权重不低于 0，保底不低于 0。 */
    public ContextProfile {
        totalTokens = Math.max(1, totalTokens);
        weights = normalize(weights);
        minimums = normalize(minimums);
    }

    /** 只给总预算的默认档：所有块权重相同、无保底。 */
    public static ContextProfile of(int totalTokens) {
        return new ContextProfile(totalTokens, Map.of(), Map.of());
    }

    /** 取权重；未配置按 1，保证所有出现的块都有被分配的机会。 */
    public int weightOf(BlockType type) {
        return weights.getOrDefault(type, 1);
    }

    /** 取保底；未配置按 0。 */
    public int minimumOf(BlockType type) {
        return minimums.getOrDefault(type, 0);
    }

    /** 过滤掉 null 和负值，避免配置写错导致分配出负数预算。 */
    private static Map<BlockType, Integer> normalize(Map<BlockType, Integer> source) {
        if (source == null || source.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<BlockType, Integer> copy = new EnumMap<>(BlockType.class);
        source.forEach((type, value) -> {
            if (type != null && value != null) {
                copy.put(type, Math.max(0, value));
            }
        });
        return Collections.unmodifiableMap(copy);
    }
}
