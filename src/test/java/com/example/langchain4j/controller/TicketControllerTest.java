package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.AuthUser;
import com.example.langchain4j.common.auth.JwtUtil;
import com.example.langchain4j.common.auth.UserRole;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.service.ConversationService;
import com.example.langchain4j.service.TicketService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * RBAC 授权契约测试：锁定管理接口的角色门槛（前端与运维对接依据）。
 * <p>
 * 覆盖三条语义：
 * <ul>
 *   <li>USER 访问 @RequireRole(ADMIN) 接口 → HTTP 403 + code=40300（RoleInterceptor 直接写响应）；</li>
 *   <li>ADMIN 访问管理接口 → 正常放行；</li>
 *   <li>未认证访问 → HTTP 401（JwtAuthFilter 先行，认证先于授权）；</li>
 *   <li>USER 访问未标注接口（本人资源）→ 正常放行。</li>
 * </ul>
 * 业务逻辑不在此验证（TicketService 整体 mock），只验证授权层的 HTTP 契约。
 */
@WebMvcTest(TicketController.class)
class TicketControllerTest {

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private TicketService ticketService;
    @MockitoBean
    private ConversationService conversationService;
    /** JwtAuthFilter 依赖；按 token 区分 USER/ADMIN 身份 */
    @MockitoBean
    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        when(jwtUtil.parse("user-token")).thenReturn(
                Optional.of(new AuthUser("alice", UserRole.USER.name())));
        when(jwtUtil.parse("admin-token")).thenReturn(
                Optional.of(new AuthUser("root", UserRole.ADMIN.name())));
        when(ticketService.listByStatus(null, 50)).thenReturn(List.of());
        when(ticketService.myTickets("alice", 50)).thenReturn(List.of());
    }

    @Test
    @DisplayName("USER 访问管理接口 /list → HTTP 403 + code=40300")
    void userDeniedOnAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/tickets/list")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.FORBIDDEN.getCode()));
    }

    @Test
    @DisplayName("ADMIN 访问管理接口 /list → HTTP 200 + code=0")
    void adminAllowedOnAdminEndpoint() throws Exception {
        mockMvc.perform(get("/api/tickets/list")
                        .header("Authorization", "Bearer admin-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("USER 访问非管理接口（我的工单）→ HTTP 200 + code=0")
    void userAllowedOnOwnEndpoint() throws Exception {
        mockMvc.perform(get("/api/tickets")
                        .header("Authorization", "Bearer user-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    @DisplayName("无 Token 访问管理接口 → HTTP 401（认证先于授权）")
    void unauthenticatedRejected() throws Exception {
        mockMvc.perform(get("/api/tickets/list"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.TOKEN_INVALID.getCode()));
    }
}
