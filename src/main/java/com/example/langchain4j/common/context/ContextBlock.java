package com.example.langchain4j.common.context;

import java.util.List;
import java.util.Objects;

/**
 * 一个待装配的上下文块。
 *
 * 用片段列表而不是单个字符串承载内容，是为了让装配器能按片丢弃：
 * 检索片段按召回顺序排列，保留前缀就等于保留得分最高的证据；历史按时间正序排列，
 * 装不下时丢弃前缀（最旧的消息）。如果调用方先拼成大字符串，丢弃粒度就只剩字符。
 *
 * @param type     块类型，决定它是否强制、是否弹性、按什么权重参与分配
 * @param segments 块内的片段列表；空白片段在构造时丢弃，避免占用预算
 */
public record ContextBlock(BlockType type, List<String> segments) {

    /** 归一化：类型必填，片段做不可变拷贝并过滤空白。 */
    public ContextBlock {
        Objects.requireNonNull(type, "type");
        segments = segments == null ? List.of() : segments.stream()
                .filter(segment -> segment != null && !segment.isBlank())
                .toList();
    }

    /** 变长参数构造：适合单个文本块（如 system prompt）。 */
    public static ContextBlock of(BlockType type, String... segments) {
        return new ContextBlock(type, segments == null ? List.of() : List.of(segments));
    }

    /** 列表构造：适合检索片段、历史消息这类多片内容。 */
    public static ContextBlock of(BlockType type, List<String> segments) {
        return new ContextBlock(type, segments);
    }

    /** 单段便捷构造；内容为空时等价于“本次没有这一块”。 */
    public static ContextBlock single(BlockType type, String content) {
        return new ContextBlock(type, content == null || content.isBlank()
                ? List.of() : List.of(content));
    }

    /** 无内容块不参与分配，避免在预算表里出现一堆零成本条目。 */
    public boolean isEmpty() {
        return segments.isEmpty();
    }
}
