package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 评测用例实体：离线回归集（问题 + 期望关键词/参考答案）。
 */
@Data
public class EvalCase {

    private Long id;
    /** 评测问题 */
    private String question;
    /** 期望关键词（JSON 数组字符串，召回评测按其在检索上下文中的命中比例打分） */
    private String keywords;
    /** 参考答案（忠实度评测参考，可空） */
    private String expectedAnswer;
    /** 是否参与评测 */
    private Boolean enabled;
    /** 创建时间 */
    private OffsetDateTime createdAt;
}
