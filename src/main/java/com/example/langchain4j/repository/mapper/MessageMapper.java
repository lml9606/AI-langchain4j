package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.Message;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 消息表 Mapper。
 */
@Mapper
public interface MessageMapper {

    int insert(Message message);

    List<Message> selectByConversationId(@Param("conversationId") String conversationId,
                                         @Param("limit") int limit);

    List<Message> selectRecentByConversationId(@Param("conversationId") String conversationId,
                                               @Param("limit") int limit);
}
