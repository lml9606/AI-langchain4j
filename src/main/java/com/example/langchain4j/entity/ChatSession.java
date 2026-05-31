package com.example.langchain4j.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 16:12
 * @Modify:
 **/
@Data
public class ChatSession {
    private Long id;

    private String userId;

    private Integer messageCount;

    private String summary;

    private Integer status;

    private Integer summaryOrder;

    private LocalDateTime activityTime;

    private LocalDateTime createTime;

    private String title;

}
