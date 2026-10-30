package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 图执行审计实体：节点级执行记录，用于可观测与问题回溯。
 */
@Data
public class GraphExecution {

    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_SUCCESS = "SUCCESS";
    public static final String STATUS_FAILED = "FAILED";

    private Long id;
    /** 单次图执行 ID（UUID 去横线） */
    private String executionId;
    /** 会话 ID（threadId） */
    private String conversationId;
    /** 图节点名；"rewrite" 为图外改写的审计记录 */
    private String nodeName;
    /** 执行状态：RUNNING / SUCCESS / FAILED（STATUS_* 常量） */
    private String status;
    /** 输入 JSON（超长截断存储） */
    private String inputJson;
    /** 输出 JSON（超长截断存储） */
    private String outputJson;
    /** 错误信息（成功时为空） */
    private String errorMsg;
    /** 节点耗时（毫秒） */
    private Long costMs;
    /** 检索根因（WorkflowState.ROOT_CAUSE_*：COMPLEX_QUERY/EXPRESSION_ISSUE/KNOWLEDGE_MISSING；正常命中为空） */
    private String rootCause;
    /** 创建时间 */
    private OffsetDateTime createdAt;
}
