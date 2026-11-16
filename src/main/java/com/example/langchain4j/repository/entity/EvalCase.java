package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 评测用例实体：离线回归集（问题 + 期望关键词/参考答案）。
 */
@Data
public class EvalCase {

    /** 场景：知识问答（默认） */
    public static final String SCENARIO_KNOWLEDGE_QA = "knowledge_qa";
    /** 场景：任务型工具调用 */
    public static final String SCENARIO_TASK = "task";

    private Long id;
    /** 场景：knowledge_qa / task */
    private String scenario;
    /** 评测问题 */
    private String question;
    /** 期望关键词（JSON 数组字符串，召回评测按其在检索上下文中的命中比例打分） */
    private String keywords;
    /** 参考答案（忠实度评测参考，可空） */
    private String expectedAnswer;
    /** 期望工具（JSON 数组字符串，任务场景工具选择评测） */
    private String expectedTools;
    /** 期望参数（JSON 对象字符串：tool -> args，任务场景参数评测） */
    private String expectedArgs;
    /** 期望是否缺参追问（任务场景，可空表示不校验） */
    private Boolean expectClarify;
    /** 是否参与评测 */
    private Boolean enabled;
    /** 创建时间 */
    private OffsetDateTime createdAt;
}
