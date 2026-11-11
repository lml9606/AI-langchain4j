package com.example.langchain4j.graph.resume.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 目标岗位画像：定向优化时由请求传入，聚合后拼入提示词。
 */
public record ResumeTargetJob(String jobName, String company, String salary, String jd,
                              List<String> skills, List<String> labels) {

    public ResumeTargetJob {
        skills = safeList(skills);
        labels = safeList(labels);
    }

    /** 转成状态里存储的 Map 形态（List<Map> 比 List<Record> 对 checkpoint 反序列化更稳） */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("jobName", nullToEmpty(jobName));
        map.put("company", nullToEmpty(company));
        map.put("salary", nullToEmpty(salary));
        map.put("jd", nullToEmpty(jd));
        map.put("skills", skills);
        map.put("labels", labels);
        return map;
    }

    public static ResumeTargetJob fromMap(Map<String, Object> map) {
        if (map == null || map.isEmpty()) {
            return new ResumeTargetJob("", "", "", "", List.of(), List.of());
        }
        return new ResumeTargetJob(
                str(map.get("jobName")),
                str(map.get("company")),
                str(map.get("salary")),
                str(map.get("jd")),
                strings(map.get("skills")),
                strings(map.get("labels")));
    }

    private static List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream().filter(Objects::nonNull).map(String::trim)
                .filter(s -> !s.isEmpty()).toList();
    }

    @SuppressWarnings("unchecked")
    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
