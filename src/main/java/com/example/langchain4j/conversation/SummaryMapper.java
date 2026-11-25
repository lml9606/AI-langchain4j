package com.example.langchain4j.conversation;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/** 会话摘要 Mapper：维护分段摘要的创建、增量覆盖、话题补名和锚点查询。 */
@Mapper
public interface SummaryMapper {

    /** 新建一段摘要；由数据库唯一约束保证同一会话同一段只存在一行。 */
    int insert(ConversationSummary summary);

    /**
     * 增量覆盖：存在则更新 content 并推进 covered_message_id（幂等，供异步摘要重试）。
     * 不更新 topic —— 补名走 {@link #updateTopic}，防止异步摘要任务用旧 topic 覆盖新名。
     */
    int upsertIncrement(ConversationSummary summary);

    /**
     * 补名专用：仅更新 topic（最新段首轮未命名时由话题归属逻辑补名），不动 content/锚点。
     *
     * @param conversationId 会话 ID
     * @param segmentId      待补名的话题段号
     * @param topic          改写器给出的新话题名
     * @return 影响行数（段不存在时为 0）
     */
    int updateTopic(@Param("conversationId") String conversationId,
                    @Param("segmentId") Integer segmentId,
                    @Param("topic") String topic);

    /** 查询会话下全部摘要段，按调用方约定的顺序返回。 */
    List<ConversationSummary> selectByConversationId(@Param("conversationId") String conversationId);

    /**
     * 单段查询：按会话 ID + 段号取单行（唯一约束保证至多一行）。
     * 供摘要任务每批开始重查最新锚点，避免全段列表扫描后在内存过滤。
     *
     * @param conversationId 会话 ID
     * @param segmentId      话题段号
     * @return 段记录；不存在时返回 null
     */
    ConversationSummary selectBySegment(@Param("conversationId") String conversationId,
                                        @Param("segmentId") int segmentId);

    /** 返回会话当前最大段号；无摘要时返回 null 或 0，具体由 SQL 决定。 */
    int maxSegmentId(@Param("conversationId") String conversationId);
}
