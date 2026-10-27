package com.example.langchain4j.service;

import com.example.langchain4j.cache.RateLimiter;
import com.example.langchain4j.cache.SessionLock;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.ChatRequest;
import com.example.langchain4j.dto.ChatResponse;
import com.example.langchain4j.event.GraphEvent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.mapper.GraphExecutionMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 编排服务：限流 → 会话 → 锁 → 图执行 → 审计 → 响应组装。
 * <p>
 * 防御设计：
 * <ul>
 *   <li>限流（429）与会话锁（409）前置拒绝，保护下游；</li>
 *   <li>图执行整体超时（504），超时后任务在后台继续跑完落 checkpoint；</li>
 *   <li>图内异常全部在节点层消化（fallback 节点保证有输出），此处只兜 graph 框架级异常；</li>
 *   <li>审计落库失败不阻断主流程（可观测性降级而非可用性降级）。</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentService {

    private static final int MAX_AUDIT_JSON_LENGTH = 4000;

    private final CompiledGraph<WorkflowState> agentGraph;
    private final RateLimiter rateLimiter;
    private final SessionLock sessionLock;
    private final ConversationService conversationService;
    private final GraphExecutionMapper graphExecutionMapper;
    private final ModelRegistry modelRegistry;
    private final Executor agentExecutor;
    private final AgentProperties properties;

    public AgentService(CompiledGraph<WorkflowState> agentGraph,
                        RateLimiter rateLimiter,
                        SessionLock sessionLock,
                        ConversationService conversationService,
                        GraphExecutionMapper graphExecutionMapper,
                        ModelRegistry modelRegistry,
                        Executor agentExecutor,
                        AgentProperties properties) {
        this.agentGraph = agentGraph;
        this.rateLimiter = rateLimiter;
        this.sessionLock = sessionLock;
        this.conversationService = conversationService;
        this.graphExecutionMapper = graphExecutionMapper;
        this.modelRegistry = modelRegistry;
        this.agentExecutor = agentExecutor;
        this.properties = properties;
    }

    /**
     * 同步对话：invoke 全图，返回最终结果。
     * <p>
     * 关键设计：conversationId 同时作为 LangGraph 的 threadId ——
     * checkpoint 按 threadId 隔离存储，因此“一个会话 = 一条 checkpoint 历史”，
     * 会话续聊/断点恢复时直接用会话 ID 找回对应的图执行状态。
     */
    public ChatResponse chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        // 1. 限流（超限抛 429）
        rateLimiter.checkAcquire(request.getUserId());
        // 2. 解析本轮模型（前端指定优先，空则默认；未知模型抛 400 快速失败）
        String model = modelRegistry.resolve(request.getModel());
        // 3. 会话初始化 + 用户消息持久化（失败抛 DB_UNAVAILABLE，快速失败）
        String conversationId = conversationService.ensureConversation(
                request.getUserId(), request.getConversationId(), request.getQuery());
        conversationService.saveMessage(conversationId, Message.ROLE_USER, request.getQuery(), model, null);
        // 4. 会话锁内执行图（同会话串行，防 checkpoint 竞态）
        WorkflowState state = sessionLock.executeWithLock(conversationId,
                () -> invokeGraph(conversationId, request.getQuery(), model));
        // 5. 助手消息持久化（结果已生成，落库失败降级不阻断响应）
        try {
            conversationService.saveMessage(conversationId, Message.ROLE_ASSISTANT,
                    state.result(), model, state.tokenUsage());
        } catch (Exception e) {
            log.warn("[agent] save assistant message failed, degrade: {}", e.getMessage());
        }
        return ChatResponse.of(conversationId, state.result(), state.fallback(),
                state.tokenUsage(), System.currentTimeMillis() - start, state.done());
    }

    /**
     * 流式对话：返回图执行事件流（含节点级审计），由 controller 转 SSE。
     * <p>
     * 与同步版的两点差异：
     * <ul>
     *   <li>agentGraph.stream 返回懒迭代器：真正开始拉取节点输出、执行图，
     *       时机在 controller 异步线程消费迭代器时，而不是本方法内；</li>
     *   <li>锁的生命周期跨整个流消费过程：迭代器耗尽（hasNext=false）才释放锁；
     *       若客户端断连导致迭代器未被消费完，锁由 Redisson Watchdog 在持锁线程消亡后兜底释放。</li>
     * </ul>
     */
    public Iterable<GraphEvent> stream(ChatRequest request) {
        rateLimiter.checkAcquire(request.getUserId());
        String model = modelRegistry.resolve(request.getModel());
        String conversationId = conversationService.ensureConversation(
                request.getUserId(), request.getConversationId(), request.getQuery());
        conversationService.saveMessage(conversationId, Message.ROLE_USER, request.getQuery(), model, null);

        // 手动加锁：锁不在这里释放，而是随返回的迭代器一起交给消费方，消费结束再释放
        AutoCloseable lock = sessionLock.lock(conversationId);
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        Map<String, Object> inputs = Map.of(WorkflowState.QUERY, request.getQuery(), WorkflowState.MODEL, model);
        var nodeOutputs = agentGraph.stream(inputs, config);
        return () -> new AuditingEventIterator(conversationId, nodeOutputs.iterator(), lock);
    }

    /**
     * 断点恢复：从最近 checkpoint 继续执行（checkpoint 不存在时返回 empty）。
     * <p>
     * 机制说明：LangGraph 的 checkpoint 是按“超级步骤”在节点执行边界落盘的
     * （每执行完一个节点保存一次完整状态）。invoke(GraphInput.resume()) 会加载
     * 该 threadId 最近一次 checkpoint，从上次中断的节点之后继续跑，
     * 而不是重头执行 —— 因此恢复耗时远小于重跑全图。
     */
    public Optional<WorkflowState> resume(String conversationId) {
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        try {
            return agentGraph.invoke(GraphInput.resume(), config);
        } catch (Exception e) {
            log.error("[agent] resume failed, thread={}", conversationId, e);
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "断点恢复失败: " + rootMessage(e));
        }
    }

    /**
     * 查询 checkpoint 快照（不存在或 saver 故障返回 empty）。
     * <p>
     * StateSnapshot 是 LangGraph 对“某线程当前执行位置”的只读视图：
     * 包含最新状态数据（state()）、下一个待执行节点（next()）等元信息，
     * 可用于排查“图跑到哪一步了”。
     */
    public Optional<StateSnapshot<WorkflowState>> getState(String threadId) {
        try {
            return Optional.ofNullable(agentGraph.getState(
                    RunnableConfig.builder().threadId(threadId).build()));
        } catch (Exception e) {
            log.warn("[agent] getState failed, thread={}: {}", threadId, e.getMessage());
            return Optional.empty();
        }
    }

    // ---- 内部实现 ----

    private WorkflowState invokeGraph(String conversationId, String query, String model) {
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        Map<String, Object> inputs = Map.of(WorkflowState.QUERY, query, WorkflowState.MODEL, model);
        long timeoutSeconds = Math.max(10, properties.getExecutionTimeoutSeconds());
        // 图执行放入专用线程池，HTTP 线程只等待结果。
        // 注意：future.get 超时后 HTTP 线程立即返回 504，但后台任务并不会被取消 ——
        // 它继续把图跑完、逐节点写 checkpoint；客户端随后可用 resume 拿回结果，
        // 这是“超时不丢任务”的折中设计（代价是图内节点耗时总和可能超过整体超时）。
        CompletableFuture<WorkflowState> future;
        try {
            future = CompletableFuture.supplyAsync(() -> {
                try {
                    return agentGraph.invoke(inputs, config).orElse(null);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            }, agentExecutor);
        } catch (RejectedExecutionException e) {
            // 线程池队列满拒绝：转为可预期的业务错误，而非 500 内部错误
            log.warn("[agent] executor overloaded, reject request, thread={}", conversationId);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }
        try {
            WorkflowState state = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (state == null) {
                audit(conversationId, "workflow", GraphExecution.STATUS_FAILED,
                        query, null, "图执行未返回状态", null);
                throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行未返回结果");
            }
            audit(conversationId, "workflow", GraphExecution.STATUS_SUCCESS,
                    query, state.result(), null, null);
            return state;
        } catch (TimeoutException e) {
            log.warn("[agent] execution timeout after {}s, thread={}, task continues in background",
                    timeoutSeconds, conversationId);
            throw new BizException(ErrorCode.AGENT_TIMEOUT);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BizException biz) {
                throw biz;
            }
            log.error("[agent] graph execution failed, thread={}", conversationId, cause);
            audit(conversationId, "workflow", GraphExecution.STATUS_FAILED, query, null, rootMessage(cause), null);
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行失败: " + rootMessage(cause));
        }
    }

    /** 审计落库（失败仅告警，不阻断主流程） */
    private void audit(String conversationId, String nodeName, String status,
                       String input, String output, String errorMsg, Long costMs) {
        try {
            GraphExecution execution = new GraphExecution();
            execution.setExecutionId(UUID.randomUUID().toString().replace("-", ""));
            execution.setConversationId(conversationId);
            execution.setNodeName(nodeName);
            execution.setStatus(status);
            execution.setInputJson(truncate(JsonUtil.toJson(Map.of("query", input == null ? "" : input))));
            execution.setOutputJson(truncate(JsonUtil.toJson(Map.of("result", output == null ? "" : output))));
            execution.setErrorMsg(errorMsg);
            execution.setCostMs(costMs);
            graphExecutionMapper.insert(execution);
        } catch (Exception e) {
            log.warn("[agent] audit insert failed, degrade: {}", e.getMessage());
        }
    }

    private String truncate(String json) {
        if (json == null || json.length() <= MAX_AUDIT_JSON_LENGTH) {
            return json;
        }
        return json.substring(0, MAX_AUDIT_JSON_LENGTH) + "...[truncated]";
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }

    /**
     * 流式事件迭代器：包装图 NodeOutput 流，逐节点审计并转换为 GraphEvent。
     * <p>
     * NodeOutput 三种形态：
     * <ul>
     *   <li>isSTART()：节点刚被调度（还未执行），推 NODE_START 事件；</li>
     *   <li>isEND()：图整体执行完毕，推 RESULT 事件携带最终答案；</li>
     *   <li>其他：单节点执行完毕，携带该节点更新后的状态，推 NODE_END 事件。
     * </ul>
     */
    private class AuditingEventIterator implements Iterator<GraphEvent> {

        private final String conversationId;
        private final Iterator<NodeOutput<WorkflowState>> delegate;
        private final AutoCloseable lock;
        private boolean closed = false;

        AuditingEventIterator(String conversationId,
                              Iterator<NodeOutput<WorkflowState>> delegate,
                              AutoCloseable lock) {
            this.conversationId = conversationId;
            this.delegate = delegate;
            this.lock = lock;
        }

        @Override
        public boolean hasNext() {
            boolean hasNext = delegate.hasNext();
            if (!hasNext) {
                closeQuietly();
            }
            return hasNext;
        }

        @Override
        public GraphEvent next() {
            NodeOutput<WorkflowState> output = delegate.next();
            WorkflowState state = output.state() != null
                    ? output.state()
                    : new WorkflowState(Map.of());
            String node = output.node();
            audit(conversationId, node, GraphExecution.STATUS_SUCCESS,
                    state.query(), state.result(), state.error(), null);
            if (output.isSTART()) {
                return GraphEvent.nodeStart(node);
            }
            if (output.isEND()) {
                return GraphEvent.result(node, state.result());
            }
            if (state.error() != null && !state.error().isBlank()) {
                return GraphEvent.error(node, state.error());
            }
            return GraphEvent.nodeEnd(node);
        }

        private void closeQuietly() {
            if (closed) {
                return;
            }
            closed = true;
            try {
                lock.close();
            } catch (Exception e) {
                log.warn("[agent] stream lock release failed: {}", e.getMessage());
            }
        }
    }
}
