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
    private String executionId;
    private String conversationId;
    private String nodeName;
    private String status;
    private String inputJson;
    private String outputJson;
    private String errorMsg;
    private Long costMs;
    private OffsetDateTime createdAt;
}
