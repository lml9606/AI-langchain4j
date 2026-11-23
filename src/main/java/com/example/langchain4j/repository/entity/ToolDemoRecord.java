package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 工具演示数据：读工具查询、写工具修改的对象。
 * 按 {@code userId} 隔离，SQL 层强制过滤，不同用户互不可见。
 */
@Data
public class ToolDemoRecord {

    public static final String STATUS_OPEN = "OPEN";
    public static final String STATUS_DONE = "DONE";

    private Long id;
    private String userId;
    private String title;
    private String content;
    /** OPEN / DONE */
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
