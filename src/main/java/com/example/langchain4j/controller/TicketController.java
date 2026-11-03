package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.RequireRole;
import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.auth.UserRole;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.TicketReplyRequest;
import com.example.langchain4j.dto.TicketRequest;
import com.example.langchain4j.repository.entity.Ticket;
import com.example.langchain4j.service.ConversationService;
import com.example.langchain4j.service.TicketService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 工单接入层：我的工单 / 运营处置（回复结单）/ 手动建单。
 * <p>
 * 角色说明：运营视角接口（列表/回复）标注 {@link RequireRole}(ADMIN)，
 * 由 RoleInterceptor 统一校验（普通用户 403）；用户侧接口（我的工单/建单/关单）全员开放。
 */
@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;
    private final ConversationService conversationService;

    public TicketController(TicketService ticketService, ConversationService conversationService) {
        this.ticketService = ticketService;
        this.conversationService = conversationService;
    }

    /** 我的工单 */
    @GetMapping
    public ApiResponse<List<Ticket>> myTickets(@RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(ticketService.myTickets(UserContext.require(), limit));
    }

    /** 运营视角：按状态列工单（status 空 = 全部；OPEN 看未结；仅管理员） */
    @RequireRole(UserRole.ADMIN)
    @GetMapping("/list")
    public ApiResponse<List<Ticket>> list(@RequestParam(required = false) String status,
                                          @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(ticketService.listByStatus(status, limit));
    }

    /** 手动建单（流式路径或前端触发；会话归属校验） */
    @PostMapping
    public ApiResponse<Ticket> create(@Valid @RequestBody TicketRequest request) {
        String userId = UserContext.require();
        conversationService.requireOwnership(request.conversationId(), userId);
        return ApiResponse.success(ticketService.createManual(
                userId, request.conversationId(), request.question(), request.rootCause()));
    }

    /** 运营回复：写入回复与处理人并置为 RESOLVED（仅管理员） */
    @RequireRole(UserRole.ADMIN)
    @PostMapping("/{id}/reply")
    public ApiResponse<Void> reply(@PathVariable long id, @Valid @RequestBody TicketReplyRequest request) {
        ticketService.reply(id, request.reply(), request.assignee());
        return ApiResponse.success();
    }

    /** 关单（用户确认解决） */
    @PostMapping("/{id}/close")
    public ApiResponse<Void> close(@PathVariable long id) {
        ticketService.close(id);
        return ApiResponse.success();
    }
}
