package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * Prompt 配置实体：DB 覆盖代码默认提示词（配置化版本化）。
 */
@Data
public class PromptConfig {

    private Long id;
    /** 提示词 key（对应 PromptTemplates 常量名） */
    private String promptKey;
    /** 生效文本（覆盖代码默认值） */
    private String promptText;
    /** 版本号（每次保存 +1） */
    private Integer version;
    /** 最后修改人 */
    private String updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
