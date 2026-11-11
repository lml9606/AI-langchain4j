package com.example.langchain4j.graph.resume.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 简历变更点：LLM 输出的 changes 数组元素。
 */
public record ResumeChange(String section, String type, String summary, String reason) {

    /** 合法变更类型（与提示词约定一致；非法值归一为 rewrote） */
    public static final String TYPE_REWRITTEN = "rewrote";

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("section", nullToEmpty(section));
        map.put("type", normalizeType(type));
        map.put("summary", nullToEmpty(summary));
        map.put("reason", nullToEmpty(reason));
        return map;
    }

    public static ResumeChange fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return new ResumeChange("", TYPE_REWRITTEN, "", "");
        }
        return new ResumeChange(
                str(map.get("section")),
                normalizeType(str(map.get("type"))),
                str(map.get("summary")),
                str(map.get("reason")));
    }

    public static String normalizeType(String type) {
        if (type == null) {
            return TYPE_REWRITTEN;
        }
        return switch (type.trim()) {
            case "reordered", "trimmed", "expanded", "keyword", "rewrote" -> type.trim();
            default -> TYPE_REWRITTEN;
        };
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
