package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.ConversationSummary;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SummaryMapper {

    int insert(ConversationSummary summary);

    /** 增量覆盖：存在则追加 content 并推进 covered_message_id（幂等，供异步摘要重试） */
    int upsertIncrement(ConversationSummary summary);

    List<ConversationSummary> selectByConversationId(@Param("conversationId") String conversationId);

    int maxSegmentId(@Param("conversationId") String conversationId);
}
