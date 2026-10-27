package com.example.langchain4j.common.util;

import org.slf4j.MDC;

import java.util.UUID;

/**
 * TraceId 工具：贯穿一次请求 / 一次图执行的日志追踪标识。
 * 通过 SLF4J MDC 自动写入日志，配合 logback pattern 中的 %X{traceId} 使用。
 */
public final class TraceIdUtil {

    public static final String MDC_KEY = "traceId";
    private static final String TRACE_ID_PREFIX = "trc";

    private TraceIdUtil() {
    }

    /** 生成并放入 MDC，返回生成的 traceId */
    public static String generateAndSet() {
        String traceId = generate();
        set(traceId);
        return traceId;
    }

    /** 生成不带横线的紧凑 ID（便于日志检索） */
    public static String generate() {
        return TRACE_ID_PREFIX + UUID.randomUUID().toString().replace("-", "");
    }

    public static void set(String traceId) {
        if (traceId != null && !traceId.isBlank()) {
            MDC.put(MDC_KEY, traceId);
        }
    }

    public static String get() {
        return MDC.get(MDC_KEY);
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }
}
