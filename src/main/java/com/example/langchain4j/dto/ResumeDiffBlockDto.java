package com.example.langchain4j.dto;

import java.util.Map;

/**
 * 行级 diff 响应块：tag = same / replace / delete / insert。
 */
public record ResumeDiffBlockDto(String tag, String left, String right) {

    public static ResumeDiffBlockDto fromMap(Map<String, Object> map) {
        if (map == null) {
            return new ResumeDiffBlockDto("same", "", "");
        }
        return new ResumeDiffBlockDto(
                str(map.get("tag")),
                str(map.get("left")),
                str(map.get("right")));
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
