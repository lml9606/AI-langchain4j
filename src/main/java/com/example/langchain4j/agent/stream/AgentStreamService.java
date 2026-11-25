package com.example.langchain4j.agent.stream;

import com.example.langchain4j.agent.AgentService;
import com.example.langchain4j.agent.dto.ChatRequest;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * SSE 接入服务：管理连接生命周期、心跳和事件发送，具体 Agent 流程复用 {@link AgentService}。
 *
 * 连接使用永不超时的 SseEmitter，靠固定心跳保活；图执行超时仍由 AgentService 控制，
 * 超时后发送 done（fallback 应答）而不是让连接悬挂。
 *
 * 客户端断开时通过 sink 返回值通知图执行停止，避免继续消耗模型与工具资源。
 */
@Slf4j
@Service
public class AgentStreamService {

    /** 心跳间隔：小于常见反向代理 60s 空闲超时，防止连接被中间层提前回收。 */
    private static final long HEARTBEAT_SECONDS = 15L;
    /** 错误消息截断上限：避免异常堆栈或超长上游报文撑爆前端。 */
    private static final int MAX_ERROR_MESSAGE_LENGTH = 300;

    private final AgentService agentService;
    private final ThreadPoolTaskExecutor agentExecutor;
    private final ScheduledExecutorService heartbeatScheduler;

    /**
     * 注入 Agent 编排服务和图执行线程池。
     *
     * 流式任务复用 agentExecutor：线程池的 TaskDecorator 会透传 traceId 与用户身份，
     * 保证 SSE 链路日志和权限上下文与同步链路一致。
     */
    public AgentStreamService(AgentService agentService,
                              @Qualifier("agentExecutor") ThreadPoolTaskExecutor agentExecutor) {
        this.agentService = agentService;
        this.agentExecutor = agentExecutor;
        this.heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "agent-sse-heartbeat");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 建立 SSE 连接并异步启动本轮对话。
     *
     * 方法立即返回 emitter；实际图执行在 agentExecutor 中完成。
     * 线程池拒绝时发送 error 事件并结束连接，不把异常抛回 MVC 层（此时响应头已提交）。
     *
     * @param request 对话请求（与同步 chat 相同）
     * @return SSE 连接，事件类型见 {@link AgentStreamEvent}
     */
    public SseEmitter stream(ChatRequest request) {
        SseEmitter emitter = new SseEmitter(0L);
        AtomicBoolean closed = new AtomicBoolean(false);
        ScheduledFuture<?> heartbeat = startHeartbeat(emitter, closed);

        emitter.onCompletion(() -> {
            closed.set(true);
            heartbeat.cancel(false);
        });
        emitter.onTimeout(() -> {
            closed.set(true);
            heartbeat.cancel(false);
        });
        emitter.onError(error -> {
            closed.set(true);
            heartbeat.cancel(false);
        });

        try {
            agentExecutor.execute(() -> run(request, emitter, closed, heartbeat));
        } catch (RejectedExecutionException e) {
            log.warn("[agent-sse] executor overloaded, reject stream request");
            send(emitter, closed,
                    AgentStreamEvent.of("error", errorData(42900, "系统繁忙，请稍后重试")));
            complete(emitter);
            heartbeat.cancel(false);
        }
        return emitter;
    }

    /**
     * 执行一次流式对话并负责收尾。
     *
     * 正常结束发送 done 后关闭连接；业务异常发送 error；客户端已断开时不再写连接。
     */
    private void run(ChatRequest request, SseEmitter emitter, AtomicBoolean closed,
                     ScheduledFuture<?> heartbeat) {
        try {
            agentService.stream(request, event -> send(emitter, closed, event));
            complete(emitter);
        } catch (BizException e) {
            log.warn("[agent-sse] business failure, code={}, message={}", e.getCode(),
                    e.getMessage());
            send(emitter, closed, AgentStreamEvent.of("error",
                    errorData(e.getCode(), e.getMessage())));
            complete(emitter);
        } catch (Exception e) {
            log.error("[agent-sse] stream failed", e);
            send(emitter, closed, AgentStreamEvent.of("error",
                    errorData(50010, truncate(e.getMessage()))));
            complete(emitter);
        } finally {
            heartbeat.cancel(false);
        }
    }

    /**
     * 发送一个 SSE 事件。
     *
     * 先检查连接状态再写；写失败视为客户端断开，返回 false 让图执行停止。
     */
    private boolean send(SseEmitter emitter, AtomicBoolean closed, AgentStreamEvent event) {
        if (closed.get()) {
            return false;
        }
        try {
            emitter.send(SseEmitter.event()
                    .name(event.type())
                    .data(JsonUtil.toJson(event.data()), MediaType.APPLICATION_JSON));
            return true;
        } catch (Exception e) {
            closed.set(true);
            log.info("[agent-sse] client disconnected, event={}, reason={}", event.type(),
                    e.getMessage());
            return false;
        }
    }

    /** 结束连接；客户端已断开时 complete 可能再次抛错，这里统一吞掉并记录。 */
    private void complete(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            log.debug("[agent-sse] complete ignored: {}", e.getMessage());
        }
    }

    /** 启动心跳：发送 SSE 注释帧，不触发前端业务事件，只用于保活。 */
    private ScheduledFuture<?> startHeartbeat(SseEmitter emitter, AtomicBoolean closed) {
        return heartbeatScheduler.scheduleAtFixedRate(() -> {
            if (closed.get()) {
                return;
            }
            try {
                emitter.send(SseEmitter.event().comment("ping"));
            } catch (Exception e) {
                closed.set(true);
                log.info("[agent-sse] heartbeat failed, client disconnected: {}", e.getMessage());
            }
        }, HEARTBEAT_SECONDS, HEARTBEAT_SECONDS, TimeUnit.SECONDS);
    }

    /** 统一错误载荷结构。 */
    private Map<String, Object> errorData(int code, String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("code", code);
        data.put("message", truncate(message));
        return data;
    }

    /** 错误消息截断；null/空白给稳定兜底。 */
    private String truncate(String message) {
        if (message == null || message.isBlank()) {
            return "流式对话执行失败";
        }
        return message.length() <= MAX_ERROR_MESSAGE_LENGTH
                ? message : message.substring(0, MAX_ERROR_MESSAGE_LENGTH);
    }

    /** 应用停机时停止心跳调度线程，避免守护线程泄漏。 */
    @PreDestroy
    public void shutdown() {
        heartbeatScheduler.shutdownNow();
    }
}
