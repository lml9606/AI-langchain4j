package com.example.langchain4j.mapper;

import com.example.langchain4j.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.data.repository.query.Param;

import java.util.List;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 18:27
 * @Modify:
 **/


@Mapper
public interface ChatSessionMapper {

    /**
     * 插入一条会话记录
     */
    int insert(ChatSession session);

    /**
     * 根据ID查询会话
     */
    ChatSession selectById(@Param("id") Long id);

    /**
     * 查询某个用户的所有会话，按活动时间倒序
     */
    List<ChatSession> selectByUserId(@Param("userId") String userId);

    /**
     * 更新会话摘要、状态、摘要顺序和活动时间
     */
    int updateSummary(@Param("id") Long id,
                      @Param("summary") String summary,
                      @Param("status") Integer status,
                      @Param("summaryOrder") Integer summaryOrder,
                      @Param("activityTime") java.time.LocalDateTime activityTime);

    /**
     * 仅更新活动时间
     */
    int updateActivityTime(@Param("id") Long id,
                           @Param("activityTime") java.time.LocalDateTime activityTime);

    /**
     * 删除会话
     */
    int deleteById(@Param("id") Long id);

    ChatSession selectByUserIdAndStatus(@Param("userId") String userId, @Param("status") Integer status);

    int updateById(ChatSession session);

}
