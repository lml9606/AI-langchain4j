package com.example.langchain4j.graph.resume.model;

import java.util.Locale;

/**
 * 简历优化模式。
 */
public enum ResumeOptimizeMode {

    /** 按目标岗位 JD 画像定向优化 */
    TARGETED,
    /** 按用户选定章节 + 提示词自由优化 */
    FREE;

    /**
     * 从请求字符串解析模式（大小写不敏感）；非法值返回 null，由调用方转 400。
     */
    public static ResumeOptimizeMode from(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
