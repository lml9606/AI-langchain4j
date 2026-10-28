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

    /** 取段内最近 limit 条消息（正序，id >= fromId），用于生成视图窗口装配 */
    List<Message> selectRecentFromId(@Param("conversationId") String conversationId,
                                     @Param("fromId") long fromId,
                                     @Param("limit") int limit);

    /** 取段内未被摘要覆盖的消息（正序，最多 limit 条），用于增量摘要压缩 */
    List<Message> selectUncovered(@Param("conversationId") String conversationId,
                                  @Param("fromId") long fromId,
                                  @Param("coveredId") Long coveredId,
                                  @Param("limit") int limit);

    /** 段内未被摘要覆盖的消息条数（摘要触发判断） */
    int countUncovered(@Param("conversationId") String conversationId,
                       @Param("fromId") long fromId,
                       @Param("coveredId") Long coveredId);
}
