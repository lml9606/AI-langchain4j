package com.example.langchain4j.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 会话表 Mapper。
 */
@Mapper
public interface ConversationMapper {

    int insert(Conversation conversation);

    Conversation selectByConversationId(@Param("conversationId") String conversationId);

    int updateStatus(@Param("conversationId") String conversationId, @Param("status") String status);

    int updateTitle(@Param("conversationId") String conversationId, @Param("title") String title);
}
