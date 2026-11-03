package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.repository.entity.Ticket;
import com.example.langchain4j.repository.mapper.TicketMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 工单服务：转人工自动建单 + 运营回复结单（运营闭环的处置通道）。
 * <p>
 * 建单策略：同一会话最近一条工单仍为 OPEN 时不重复建单（避免每轮转人工都刷单）；
 * 建单失败降级不阻断主流程（图执行照常返回转人工文案，工单可人工补建）。
 */
@Slf4j
@Service
public class TicketService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final TicketMapper ticketMapper;

    public TicketService(TicketMapper ticketMapper) {
        this.ticketMapper = ticketMapper;
    }

    /**
     * 转人工自动建单（同步对话/resume 路径在拿到图状态后调用）。
     * 落库失败仅告警不抛出（运营闭环不可用不能反噬主流程可用性）。
     */
    public void createIfAbsent(String userId, String conversationId, String question, String rootCause) {
        try {
            Ticket latest = ticketMapper.selectLatestByConversationId(conversationId);
            if (latest != null && Ticket.STATUS_OPEN.equals(latest.getStatus())) {
                log.info("[ticket] skip create, open ticket exists: {}", latest.getTicketNo());
                return;
            }
            create(userId, conversationId, question, rootCause);
        } catch (Exception e) {
            log.warn("[ticket] auto create failed, degrade: {}", e.getMessage());
        }
    }

    /** 手动建单（流式路径/前端触发；重复建单由调用方语义决定，此处不做去重） */
    @Transactional(rollbackFor = Exception.class)
    public Ticket createManual(String userId, String conversationId, String question, String rootCause) {
        return create(userId, conversationId, question, rootCause);
    }

    private Ticket create(String userId, String conversationId, String question, String rootCause) {
        Ticket ticket = new Ticket();
        ticket.setTicketNo(generateTicketNo());
        ticket.setUserId(userId);
        ticket.setConversationId(conversationId);
        ticket.setQuestion(question == null || question.isBlank() ? "（未提供）" : question);
        ticket.setRootCause(rootCause == null || rootCause.isBlank() ? null : rootCause);
        ticket.setStatus(Ticket.STATUS_OPEN);
        ticketMapper.insert(ticket);
        log.info("[ticket] created, no={}, user={}, rootCause={}", ticket.getTicketNo(), userId, rootCause);
        return ticket;
    }

    /** 我的工单 */
    public List<Ticket> myTickets(String userId, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<Ticket> list = ticketMapper.selectByUserId(userId, bounded);
        return list == null ? List.of() : list;
    }

    public List<Ticket> myTickets(String userId) {
        return myTickets(userId, DEFAULT_LIMIT);
    }

    /** 运营视角：按状态列工单（status 为空 = 全部） */
    public List<Ticket> listByStatus(String status, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<Ticket> list = ticketMapper.selectByStatus(status, bounded);
        return list == null ? List.of() : list;
    }

    /** 运营回复：写入回复与处理人并置为 RESOLVED */
    @Transactional(rollbackFor = Exception.class)
    public void reply(long id, String reply, String assignee) {
        Ticket ticket = requireTicket(id);
        if (reply == null || reply.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "回复内容不能为空");
        }
        ticketMapper.updateReply(id, reply.trim(), assignee, Ticket.STATUS_RESOLVED);
        log.info("[ticket] replied, id={}, assignee={}", id, assignee);
    }

    /** 关单（用户确认解决后关闭） */
    @Transactional(rollbackFor = Exception.class)
    public void close(long id) {
        Ticket ticket = requireTicket(id);
        ticketMapper.updateStatus(id, Ticket.STATUS_CLOSED);
        log.info("[ticket] closed, id={}", id);
    }

    private Ticket requireTicket(long id) {
        Ticket ticket = ticketMapper.selectById(id);
        if (ticket == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "工单不存在: " + id);
        }
        return ticket;
    }

    /** 工单号：TK + 时间戳 + 随机后缀（可读 + 防猜测） */
    private String generateTicketNo() {
        return "TK" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"))
                + String.format("%04d", ThreadLocalRandom.current().nextInt(10000));
    }
}
