package com.example.langchain4j.agent;

import com.example.langchain4j.agent.dto.AgentResumeRequest;
import com.example.langchain4j.agent.dto.ChatRequest;
import com.example.langchain4j.agent.dto.ChatResponse;
import com.example.langchain4j.agent.dto.ConfirmRequest;
import com.example.langchain4j.agent.dto.MessageDto;
import com.example.langchain4j.agent.stream.AgentStreamService;
import com.example.langchain4j.auth.security.UserContext;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.conversation.ConversationService;
import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
    private final AgentStreamService agentStreamService;

    public AgentController(AgentService agentService,
                           ConversationService conversationService,
                           AgentStreamService agentStreamService) {
        this.agentService = agentService;
        this.conversationService = conversationService;
        this.agentStreamService = agentStreamService;
    }

    /**
     * 同步对话：invoke 主图全流程，返回最终结果（非流式）。
     *
     * @param request 对话请求：query 必填；conversationId 空 = 新会话（响应返回新 32 位会话 ID，
     *                续聊需回传该 ID）；model 按名路由（ModelRegistry，见 agent.deepseek.model-names）
     * @return 统一响应包裹的对话结果（conversationId / answer / 模型与降级标记等）
     */
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(agentService.chat(request));
    }

    /**
     * 流式对话：SSE 推送 meta → node（图节点进度）→ done（最终结果），失败推送 error。
     *
     * 入参与同步 chat 完全一致；响应不再包 ApiResponse，事件 data 都是 JSON 字符串。
     * 客户端断开后服务端会停止图执行，不写入半成品助手消息。
     *
     * @param request 对话请求（query 必填；conversationId 空 = 新会话）
     * @return SSE 连接（事件名 meta/node/done/error，另有心跳注释帧）
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@Valid @RequestBody ChatRequest request) {
        return agentStreamService.stream(request);
    }

    /**
     * 确认写操作：必须同时传 conversationId 与上一轮 chat 返回的 confirmationId，
     * approved=false 表示取消。动作过期返回 404；已成功则按台账幂等重放，执行中返回 409。
     */
    @PostMapping("/confirm")
    public ApiResponse<ChatResponse> confirm(@Valid @RequestBody ConfirmRequest request) {
        return ApiResponse.success(agentService.confirm(request));
    }

    /**
     * 断点恢复：从该会话最近 checkpoint 继续执行（POST 便于扩展恢复参数）。
     *
     * 特殊条件：会话无待恢复任务（最后一轮已闭环或无可用 checkpoint）时返回 404 业务码。
     *
     * @param request 恢复请求（conversationId 即图 threadId；归属校验：404 不存在 / 403 他人会话）
     * @return 恢复执行后的图状态 data；无待恢复任务返回 404 错误码
     */
    @PostMapping("/resume")
    public ApiResponse<Map<String, Object>> resume(@Valid @RequestBody AgentResumeRequest request) {
        requireOwnership(request.conversationId());
        return agentService.resume(request.conversationId())
                .map(state -> ApiResponse.success(state.data()))
                .orElse(ApiResponse.error(ErrorCode.NOT_FOUND,
                        "无待恢复的任务：该会话最后一轮已闭环或没有可用的 checkpoint"));
    }

    /**
     * 查询 checkpoint 执行状态（节点级快照信息）。
     *
     * @param threadId 图线程 ID（= 会话 ID）；归属校验：404 不存在 / 403 他人会话
     * @return 最新 checkpoint 的状态 data；无记录返回 404 错误码
     */
    @GetMapping("/state/{threadId}")
    public ApiResponse<Map<String, Object>> state(@PathVariable String threadId) {
        requireOwnership(threadId);
        return agentService.getState(threadId)
                .map(snapshot -> ApiResponse.success(snapshot.state().data()))
                .orElse(ApiResponse.error(ErrorCode.NOT_FOUND, "无 checkpoint 记录"));
    }

    /**
     * 会话历史消息列表（全量事实源 ai_message，按时间顺序）。
     *
     * @param conversationId 会话 ID；归属校验：404 不存在 / 403 他人会话
     * @param limit          返回条数（默认 50，上限 200）
     * @return 会话消息列表（role / content / model / tokenUsage / createdAt）
     */
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
