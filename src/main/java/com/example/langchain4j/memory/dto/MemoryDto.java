package com.example.langchain4j.memory.dto;

import com.example.langchain4j.memory.UserMemory;

import java.time.OffsetDateTime;

/**
 * 长期记忆对外视图：不暴露内部 user_id（身份以 Token 为准），只返回用户可读字段。
 */
public record MemoryDto(Long id,
                        String memoryType,
                        String memoryKey,
                        String content,
                        Double importance,
                        Double confidence,
                        String status,
                        Integer seenCount,
                        String sourceConversationId,
                        OffsetDateTime expiresAt,
                        OffsetDateTime createdAt,
                        OffsetDateTime updatedAt) {

    /** 实体转对外视图。 */
    public static MemoryDto of(UserMemory memory) {
        return new MemoryDto(memory.getId(), memory.getMemoryType(), memory.getMemoryKey(),
                memory.getContent(), memory.getImportance(), memory.getConfidence(),
                memory.getStatus(), memory.getSeenCount(), memory.getSourceConversationId(),
                memory.getExpiresAt(), memory.getCreatedAt(), memory.getUpdatedAt());
    }
}
