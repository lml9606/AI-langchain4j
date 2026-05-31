package com.example.langchain4j.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 16:26
 * @Modify:
 **/
@Data
public class ChatMessage {
    private Long id;

    private String userId;

    private String role;

    private String content;

    private Integer messageOrder;
    private boolean summarized;
    public LocalDateTime createTime;
}
