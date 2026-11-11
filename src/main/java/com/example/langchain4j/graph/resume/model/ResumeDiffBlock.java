package com.example.langchain4j.graph.resume.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 行级 diff 块：tag = same / replace / delete / insert。
 */
public record ResumeDiffBlock(String tag, String left, String right) {

    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tag", tag == null ? "same" : tag);
        map.put("left", left == null ? "" : left);
        map.put("right", right == null ? "" : right);
        return map;
    }
}
