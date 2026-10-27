package com.example.langchain4j.dto;

import java.time.OffsetDateTime;

/**
 * 会话消息视图（对外传输对象，隐藏实体内部字段）。
 */
public record MessageDto(String role,
                         String content,
                         String model,
                         Integer tokenUsage,
                         OffsetDateTime createdAt) {
}
