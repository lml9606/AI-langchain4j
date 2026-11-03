package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.Ticket;
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

    Ticket selectByTicketNo(@Param("ticketNo") String ticketNo);

    /** 会话最近一条工单（任意状态；用于“同会话不重复建单”判断） */
    Ticket selectLatestByConversationId(@Param("conversationId") String conversationId);

    List<Ticket> selectByUserId(@Param("userId") String userId, @Param("limit") int limit);

    /** 运营视角：按状态列工单（全部/未结） */
    List<Ticket> selectByStatus(@Param("status") String status, @Param("limit") int limit);

    int updateReply(@Param("id") long id, @Param("reply") String reply,
                    @Param("assignee") String assignee, @Param("status") String status);

    int updateStatus(@Param("id") long id, @Param("status") String status);
}
