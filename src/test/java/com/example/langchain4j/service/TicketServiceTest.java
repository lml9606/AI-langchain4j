package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.Ticket;
import com.example.langchain4j.repository.mapper.TicketMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 工单用户侧操作归属校验单测。 */
@ExtendWith(MockitoExtension.class)
class TicketServiceTest {

    @Mock
    private TicketMapper ticketMapper;

    private TicketService service;

    @BeforeEach
    void setUp() {
        service = new TicketService(ticketMapper, new AgentProperties());
    }

    @Test
    @DisplayName("关闭本人工单 → SQL 同时按 id + user_id 过滤")
    void closeOwnTicket() {
        Ticket ticket = new Ticket();
        ticket.setId(9L);
        ticket.setUserId("alice");
        when(ticketMapper.selectByIdAndUserId(9L, "alice")).thenReturn(ticket);
        when(ticketMapper.updateStatus(9L, Ticket.STATUS_CLOSED, "alice")).thenReturn(1);

        service.close(9L, "alice");

        verify(ticketMapper).updateStatus(9L, Ticket.STATUS_CLOSED, "alice");
    }

    @Test
    @DisplayName("关闭他人工单 → 404，且不执行更新")
    void cannotCloseOtherUsersTicket() {
        when(ticketMapper.selectByIdAndUserId(9L, "bob")).thenReturn(null);

        assertThatThrownBy(() -> service.close(9L, "bob"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());

        verify(ticketMapper, never()).updateStatus(9L, Ticket.STATUS_CLOSED, "bob");
    }

    @Test
    @DisplayName("查询后工单被删除 → 更新影响 0 行按 404 处理")
    void closeRaceReturnsNotFound() {
        Ticket ticket = new Ticket();
        ticket.setId(9L);
        ticket.setUserId("alice");
        when(ticketMapper.selectByIdAndUserId(9L, "alice")).thenReturn(ticket);
        when(ticketMapper.updateStatus(9L, Ticket.STATUS_CLOSED, "alice")).thenReturn(0);

        assertThatThrownBy(() -> service.close(9L, "alice"))
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getCode())
                .isEqualTo(ErrorCode.NOT_FOUND.getCode());
    }
}
