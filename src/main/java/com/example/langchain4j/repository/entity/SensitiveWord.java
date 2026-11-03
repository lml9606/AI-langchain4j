package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 敏感词实体：输出侧审核词表（命中即拦截）。
 */
@Data
public class SensitiveWord {

    private Long id;
    /** 敏感词（字面包含匹配） */
    private String word;
    /** 是否启用 */
    private Boolean enabled;
    private OffsetDateTime createdAt;
}
