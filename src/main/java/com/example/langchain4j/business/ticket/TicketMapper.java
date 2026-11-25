package com.example.langchain4j.business.ticket;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工单表 Mapper。
 */
@Mapper
public interface TicketMapper {

    int insert(Ticket ticket);

    Ticket selectById(@Param("id") long id);

    /** 按 id + 归属用户查询（用户侧操作必须带 user_id，防水平越权） */
    Ticket selectByIdAndUserId(@Param("id") long id, @Param("userId") String userId);

    Ticket selectByTicketNo(@Param("ticketNo") String ticketNo);

    /** 会话最近一条工单（任意状态；用于“同会话不重复建单”判断） */
    Ticket selectLatestByConversationId(@Param("conversationId") String conversationId);

    List<Ticket> selectByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /** 运营视角：按状态列工单（全部/未结） */
    List<Ticket> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    int updateReply(@Param("id") long id, @Param("reply") String reply,
                    @Param("assignee") String assignee, @Param("status") String status);

    /** 按 id + 归属用户更新状态（SQL 层再次过滤 user_id，避免 TOCTOU） */
    int updateStatus(@Param("id") long id, @Param("status") String status,
                     @Param("userId") String userId);
}
