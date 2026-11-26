package com.example.langchain4j.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 记忆抽取任务 Mapper：用 message_id 做幂等闸门。
 *
 * 同一条消息只允许一个抽取任务进入 RUNNING；重复投递、服务重试都会在
 * insert 阶段被唯一键挡掉，避免同一条对话被反复沉淀成多条记忆。
 */
@Mapper
public interface MemoryExtractTaskMapper {

    /**
     * 抢占一条消息的抽取权。
     *
     * @param messageId      消息幂等键（消息 ID 或哈希）
     * @param userId         当前用户
     * @param conversationId 来源会话
     * @return 1=抢占成功；0=已存在（重复投递，直接跳过）
     */
    int insertIfAbsent(@Param("messageId") String messageId,
                       @Param("userId") String userId,
                       @Param("conversationId") String conversationId);

    /**
     * 更新抽取任务最终状态。
     *
     * @param messageId 消息幂等键
     * @param status    DONE / FAILED
     * @return 影响行数
     */
    int markStatus(@Param("messageId") String messageId, @Param("status") String status);
}
