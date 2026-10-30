package com.example.langchain4j.common.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * JSON 工具：全局共享一个配置完备的 ObjectMapper（禁用失败时静默降级，避免脏数据污染）。
 */
public final class JsonUtil {

    private static final ObjectMapper MAPPER = buildMapper();

    private JsonUtil() {
    }

    private static ObjectMapper buildMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        // 反序列化：未知字段容忍，避免上游模型加字段导致崩溃
        mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // 序列化：时间统一 ISO-8601，不输出空值
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return mapper;
    }

    public static ObjectMapper mapper() {
        return MAPPER;
    }

    /** 返回独立副本：需要修改配置的调用方（如序列化器）不得污染全局实例 */
    public static ObjectMapper copyMapper() {
        return MAPPER.copy();
    }

    public static String toJson(Object obj) {
        if (obj == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            // 序列化失败不阻断主流程，返回类名占位便于排查
            return "{\"error\":\"serialize_failed\",\"type\":\"" + obj.getClass().getSimpleName() + "\"}";
        }
    }

    public static <T> T fromJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    public static <T> T fromJson(String json, TypeReference<T> typeReference) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return MAPPER.readValue(json, typeReference);
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("JSON 反序列化失败: " + e.getOriginalMessage(), e);
        }
    }

    /**
     * 从 LLM 原始输出中提取 JSON 对象片段：截取第一个 '{' 到最后一个 '}'，
     * 防御模型在 JSON 外包裹 ```json 围栏或说明文字。
     * <p>
     * 统一约定：提取失败（空输入/无花括号）返回空串而非抛异常，
     * 配合 fromJson 的空输入返回 null，调用方按各自降级路径处理（fail-open）。
     *
     * @param raw LLM 原始输出（可能包裹说明文字）
     * @return JSON 对象片段；提取失败返回空串
     */
    public static String extractJsonObject(String raw) {
        // 步骤1：空输入直接返回空串，交给调用方降级
        if (raw == null || raw.isBlank()) {
            return "";
        }
        // 步骤2：定位第一个 '{' 与最后一个 '}'，截取中间片段
        int start = raw.indexOf('{');
        int end = raw.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return raw.substring(start, end + 1);
        }
        // 步骤3：没有花括号视为提取失败，返回空串（fromJson 空输入返回 null，走调用方降级）
        return "";
    }
}
