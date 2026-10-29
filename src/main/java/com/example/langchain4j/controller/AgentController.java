package com.example.langchain4j.controller;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.dto.ChatRequest;
import com.example.langchain4j.dto.ChatResponse;
import com.example.langchain4j.dto.MessageDto;
import com.example.langchain4j.dto.ResumeRequest;
import com.example.langchain4j.graph.GraphEvent;
import com.example.langchain4j.service.AgentService;
import com.example.langchain4j.service.ConversationService;
import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.scheduling.TaskScheduler;
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
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;

/**
 * Agent 接入层：同步对话 / SSE 流式 / 断点恢复 / checkpoint 状态 / 历史消息。
 */
@Slf4j
@RestController
@RequestMapping("/api/agent")
public class AgentController {

    private static final long HEARTBEAT_INTERVAL_SECONDS = 15;

    private final AgentService agentService;
    private final ConversationService conversationService;
    private final Executor agentExecutor;
    private final TaskScheduler taskScheduler;

    public AgentController(AgentService agentService,
                           ConversationService conversationService,
                           Executor agentExecutor,
                           TaskScheduler taskScheduler) {
        this.agentService = agentService;
        this.conversationService = conversationService;
        this.agentExecutor = agentExecutor;
        this.taskScheduler = taskScheduler;
    }

    /** 同步对话（invoke 全图，返回最终结果） */
    @PostMapping("/chat")
    public ApiResponse<ChatResponse> chat(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(agentService.chat(request));
    }

    /**
     * SSE 流式对话：节点级进度事件 + 15s 心跳保活。
     * 限流/会话锁冲突在流建立前抛出（走全局异常 JSON 响应）。
     * <p>
     * 时序说明：
     * <ol>
     *   <li>agentService.stream 只加锁 + 返回懒迭代器，图尚未执行；</li>
     *   <li>本方法立即返回 SseEmitter（HTTP 连接转为异步），推送交给 agentExecutor 线程；</li>
     *   <li>消费线程每拉到一个 GraphEvent 就发一条 SSE，拉不到时阻塞等待下一节点；</li>
     *   <li>消费完成或异常后 complete/completeWithError 结束流，锁随之释放。</li>
     * </ol>
     * 心跳注释帧（comment 类型）不干扰前端 event.data 解析，仅用于穿透代理/网关的读超时。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
        // 前置校验在创建 emitter 前执行：429/409 等业务异常以 JSON 形式返回
        Iterable<GraphEvent> events = agentService.stream(request);

        SseEmitter emitter = new SseEmitter(0L); // 永不超时，靠心跳防代理断连
        ScheduledFuture<?> heartbeat = taskScheduler.scheduleAtFixedRate(
                () -> sendQuietly(emitter, SseEmitter.event().comment("hb")),
                java.time.Instant.now().plusSeconds(HEARTBEAT_INTERVAL_SECONDS),
                java.time.Duration.ofSeconds(HEARTBEAT_INTERVAL_SECONDS));
        Runnable cancelHeartbeat = () -> heartbeat.cancel(false);
        emitter.onTimeout(() -> {
            log.warn("[sse] timeout, cancel heartbeat");
            heartbeat.cancel(false);
        });
        emitter.onError(e -> {
            log.warn("[sse] client error, cancel heartbeat: {}", e.getMessage());
            heartbeat.cancel(false);
        });

        try {
            // 推送必须异步：SSE 的本质是长连接，若在 HTTP 线程同步消费图事件流，
            // 该线程会被长时间占用直到图跑完，耗尽 Tomcat 工作线程池
            agentExecutor.execute(() -> {
                try {
                    for (GraphEvent event : events) {
                        sendQuietly(emitter, SseEmitter.event()
                                .name(event.type())
                                .data(JsonUtil.toJson(event)));
                    }
                    emitter.complete();
                } catch (Exception e) {
                    log.error("[sse] stream execution failed", e);
                    sendQuietly(emitter, SseEmitter.event()
                            .name(GraphEvent.TYPE_ERROR)
                            .data(JsonUtil.toJson(GraphEvent.error("stream", rootMessage(e)))));
                    emitter.completeWithError(e);
                } finally {
                    heartbeat.cancel(false);
                }
            });
        } catch (RejectedExecutionException e) {
            cancelHeartbeat.run();
            log.warn("[sse] executor overloaded, reject stream request");
            throw new BizException(
                    ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }
        return emitter;
    }

    /** 断点恢复：从最近 checkpoint 继续执行（POST 便于扩展恢复参数） */
    @PostMapping("/resume")
    public ApiResponse<Map<String, Object>> resume(@Valid @RequestBody ResumeRequest request) {
        return agentService.resume(request.conversationId())
                .map(state -> ApiResponse.success(state.data()))
                .orElse(ApiResponse.error(ErrorCode.NOT_FOUND,
                        "无待恢复的任务：该会话最后一轮已闭环或没有可用的 checkpoint"));
    }

    /** 查询 checkpoint 执行状态 */
    @GetMapping("/state/{threadId}")
    public ApiResponse<Map<String, Object>> state(@PathVariable String threadId) {
        return agentService.getState(threadId)
                .map(snapshot -> ApiResponse.success(snapshot.state().data()))
                .orElse(ApiResponse.error(ErrorCode.NOT_FOUND, "无 checkpoint 记录"));
    }

    /** 会话历史消息（默认 50 条，上限 200） */
    @GetMapping("/conversation/{id}/messages")
    public ApiResponse<List<MessageDto>> messages(@PathVariable("id") String conversationId,
                                                  @RequestParam(defaultValue = "50") int limit) {
        List<MessageDto> messages = conversationService.listMessages(conversationId, limit).stream()
                .map(m -> new MessageDto(m.getRole(), m.getContent(), m.getModel(),
                        m.getTokenUsage(), m.getCreatedAt()))
                .toList();
        return ApiResponse.success(messages);
    }

    private void sendQuietly(SseEmitter emitter, SseEmitter.SseEventBuilder builder) {
        try {
            emitter.send(builder);
        } catch (Exception e) {
            log.debug("[sse] send failed (client disconnected?): {}", e.getMessage());
        }
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }
}
