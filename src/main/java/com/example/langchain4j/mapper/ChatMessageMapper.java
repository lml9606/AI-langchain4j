package com.example.langchain4j.mapper;

import com.example.langchain4j.entity.ChatMessage;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 18:28
 * @Modify:
 **/


@Mapper
public interface ChatMessageMapper {

    /**
     * 插入一条消息
     */
    int insert(ChatMessage message);

    /**
     * 根据ID查询消息
     */
    ChatMessage selectById(@Param("id") Long id);

    /**
     * 查询某个用户的所有消息，按消息顺序升序排列
     */
    List<ChatMessage> selectByUserId(@Param("userId") String userId);

    /**
     * 根据用户ID和消息顺序查询单条消息
     */
    ChatMessage selectByUserIdAndOrder(@Param("userId") String userId,
                                       @Param("messageOrder") Integer messageOrder);

    /**
     * 更新消息的摘要标记
     */
    int updateSummarizedStatus(@Param("id") Long id,
                               @Param("summarized") Boolean summarized);

    /**
     * 删除一条消息
     */
    int deleteById(@Param("id") Long id);

    /**
     * 根据用户ID删除所有消息
     */
    int getMaxMessageOrder(@Param("userId") String userId);

/**
 * 根据会话ID和是否已汇总状态查询记录数量
 * @param sessionId 会话ID，用于筛选特定会话的记录
 * @return 符合条件的记录数量
 */
    int countBySessionIdAndSummarizedFalse(@Param("sessionId") Long sessionId);
    /**
     * 获取会话中未摘要的消息，按消息顺序降序排列
     * @param sessionId 会话ID
     * @param limit 最大返回条数（可选）
     * @return 消息列表
     */
    List<ChatMessage> getMessageBySummarizeOrderMessageOrderAsc(@Param("sessionId") Long sessionId,
                                                                 @Param("limit") Integer limit);
}
