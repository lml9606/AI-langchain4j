package com.example.langchain4j.dto;

import java.util.Map;

/**
 * 变更点响应：改了什么、为什么改。
 */
public record ResumeChangeDto(String section, String type, String summary, String reason) {

    public static ResumeChangeDto fromMap(Map<String, Object> map) {
        if (map == null) {
            return new ResumeChangeDto("", "rewrote", "", "");
        }
        return new ResumeChangeDto(
                str(map.get("section")),
                str(map.get("type")),
                str(map.get("summary")),
                str(map.get("reason")));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
