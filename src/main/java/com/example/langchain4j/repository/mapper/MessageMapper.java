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

    /** 改写视图专用：跨话题取最近 limit 条消息（正序）——指代消解需要最近的对话流，不进生成 */
    List<Message> selectRecentByConversationId(@Param("conversationId") String conversationId,
                                               @Param("limit") int limit);

    /** 生成视图专用：取当前话题段内最近 limit 条消息（正序，按段号过滤）——其他话题的消息一条不带 */
    List<Message> selectRecentBySegment(@Param("conversationId") String conversationId,
                                        @Param("segmentId") int segmentId,
                                        @Param("limit") int limit);

    /** 取段内未被摘要覆盖的消息（正序，最多 limit 条，按段号过滤），用于增量摘要压缩 */
    List<Message> selectUncovered(@Param("conversationId") String conversationId,
                                  @Param("segmentId") int segmentId,
                                  @Param("coveredId") Long coveredId,
                                  @Param("limit") int limit);

    /** 段内未被摘要覆盖的消息条数（摘要触发判断，按段号过滤） */
    int countUncovered(@Param("conversationId") String conversationId,
                       @Param("segmentId") int segmentId,
                       @Param("coveredId") Long coveredId);

    /** 回填消息的话题段归属（user 消息落库 → 段归属决策 → 回填段号） */
    int updateSegmentId(@Param("id") Long id, @Param("segmentId") int segmentId);

    /** 最新一条 user 消息的段号（assistant 落库时继承同轮 user 的段归属） */
    Integer selectLatestUserSegmentId(@Param("conversationId") String conversationId);
}
