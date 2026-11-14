package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.ChatRequest;
import com.example.langchain4j.dto.ChatResponse;
import com.example.langchain4j.dto.MessageDto;
import com.example.langchain4j.dto.ResumeRequest;
import com.example.langchain4j.service.AgentService;
import com.example.langchain4j.service.ConversationService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Agent 接入层：同步对话 / 断点恢复 / checkpoint 状态 / 历史消息。
 */
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private final AgentService agentService;
    private final ConversationService conversationService;

    public AgentController(AgentService agentService,
                           ConversationService conversationService) {
        this.agentService = agentService;
        this.conversationService = conversationService;
    }

    /** 同步对话（invoke 全图，返回最终结果） */
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(agentService.chat(request));
    }

    /** 断点恢复：从最近 checkpoint 继续执行（POST 便于扩展恢复参数） */
    @PostMapping("/resume")
    public ApiResponse<Map<String, Object>> resume(@Valid @RequestBody ResumeRequest request) {
        requireOwnership(request.conversationId());
        return agentService.resume(request.conversationId())
                .map(state -> ApiResponse.success(state.data()))
                .orElse(ApiResponse.error(ErrorCode.NOT_FOUND,
                        "无待恢复的任务：该会话最后一轮已闭环或没有可用的 checkpoint"));
    }

    /** 查询 checkpoint 执行状态 */
    @GetMapping("/state/{threadId}")
    public ApiResponse<Map<String, Object>> state(@PathVariable String threadId) {
        requireOwnership(threadId);
        return agentService.getState(threadId)
                .map(snapshot -> ApiResponse.success(snapshot.state().data()))
                .orElse(ApiResponse.error(ErrorCode.NOT_FOUND, "无 checkpoint 记录"));
    }

    /** 会话历史消息（默认 50 条，上限 200） */
    @GetMapping("/conversation/{id}/messages")
    public ApiResponse<List<MessageDto>> messages(@PathVariable("id") String conversationId,
                                                  @RequestParam(defaultValue = "50") int limit) {
        requireOwnership(conversationId);
        List<MessageDto> messages = conversationService.listMessages(conversationId, limit).stream()
                .map(m -> new MessageDto(m.getRole(), m.getContent(), m.getModel(),
                        m.getTokenUsage(), m.getCreatedAt()))
                .toList();
        return ApiResponse.success(messages);
    }

    /** 会话归属校验：threadId/会话 ID 直查接口的水平越权防御（404 不存在 / 403 他人会话） */
    private void requireOwnership(String conversationId) {
        conversationService.requireOwnership(conversationId, UserContext.require());
    }
}
