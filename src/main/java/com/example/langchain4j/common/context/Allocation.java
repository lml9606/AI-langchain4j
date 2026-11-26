package com.example.langchain4j.common.context;

import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 一次预算分配的结果。
 *
 * 调用点按类型取回已经裁剪好的内容即可，不再自己算字符数；
 * logLine 提供统一的一行观测，能看出每块用了多少、丢了什么、还剩多少额度。
 *
 * @param scene       调用场景
 * @param segments    每块保留下来的片段（已裁剪）
 * @param usedTokens  每块实际占用的 token（含片段间分隔符）
 * @param dropReasons 每块的丢弃说明；没有被丢弃的块不出现
 * @param totalUsed   全部分块实际占用
 * @param totalBudget 本次总预算
 */
public record Allocation(ContextScene scene,
                         Map<BlockType, List<String>> segments,
                         Map<BlockType, Integer> usedTokens,
                         Map<BlockType, String> dropReasons,
                         int totalUsed,
                         int totalBudget) {

    /** 归一化：所有映射做不可变拷贝，保证分配结果输出后不被调用方改写。 */
    public Allocation {
        segments = immutableSegments(segments);
        usedTokens = immutableTokens(usedTokens);
        dropReasons = immutableReasons(dropReasons);
    }

    /** 取某块的完整文本；块不存在返回空串（调用方可以直接拼接，无需判空）。 */
    public String content(BlockType type) {
        return String.join("\n\n", segments.getOrDefault(type, List.of()));
    }

    /** 取某块的片段列表；历史消息这类需要逐条渲染的场景用这个。 */
    public List<String> segments(BlockType type) {
        return segments.getOrDefault(type, List.of());
    }

    /** 某块是否被分配了内容。 */
    public boolean has(BlockType type) {
        return !segments.getOrDefault(type, List.of()).isEmpty();
    }

    /** 剩余未使用额度，用于判断配置偏松还是偏紧。 */
    public int slack() {
        return Math.max(0, totalBudget - totalUsed);
    }

    /**
     * 生成一行结构化日志。
     *
     * 字段固定为 scene / total / slack / 各块用量 / dropped，
     * 便于用日志检索直接观察预算是否被某一块长期吃满。
     */
    public String logLine() {
        StringBuilder sb = new StringBuilder(160);
        sb.append("[context] scene=").append(scene)
                .append(" total=").append(totalUsed).append('/').append(totalBudget)
                .append(" slack=").append(slack());
        for (BlockType type : BlockType.values()) {
            Integer used = usedTokens.get(type);
            if (used == null) {
                continue;
            }
            sb.append(' ').append(type.name().toLowerCase()).append('=').append(used);
            if (type == BlockType.HISTORY) {
                sb.append('(').append(segments.getOrDefault(type, List.of()).size()).append("条)");
            }
        }
        sb.append(" dropped=");
        if (dropReasons.isEmpty()) {
            sb.append("none");
        } else {
            sb.append(dropReasons.entrySet().stream()
                    .map(entry -> entry.getKey().name().toLowerCase() + ":" + entry.getValue())
                    .collect(Collectors.joining(",")));
        }
        return sb.toString();
    }

    /** 片段映射的不可变拷贝；空值统一转空列表。 */
    private static Map<BlockType, List<String>> immutableSegments(Map<BlockType, List<String>> source) {
        Map<BlockType, List<String>> copy = new EnumMap<>(BlockType.class);
        if (source != null) {
            source.forEach((type, value) -> {
                if (type != null) {
                    copy.put(type, value == null ? List.of() : List.copyOf(value));
                }
            });
        }
        return Collections.unmodifiableMap(copy);
    }

    /** token 用量映射的不可变拷贝。 */
    private static Map<BlockType, Integer> immutableTokens(Map<BlockType, Integer> source) {
        Map<BlockType, Integer> copy = new EnumMap<>(BlockType.class);
        if (source != null) {
            copy.putAll(source);
        }
        return Collections.unmodifiableMap(copy);
    }

    /** 丢弃说明映射的不可变拷贝。 */
    private static Map<BlockType, String> immutableReasons(Map<BlockType, String> source) {
        Map<BlockType, String> copy = new EnumMap<>(BlockType.class);
        if (source != null) {
            copy.putAll(source);
        }
        return Collections.unmodifiableMap(copy);
    }
}
