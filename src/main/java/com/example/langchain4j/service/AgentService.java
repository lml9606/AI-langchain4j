package com.example.langchain4j.service;

import com.example.langchain4j.cache.RateLimiter;
import com.example.langchain4j.cache.SessionLock;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.ChatRequest;
import com.example.langchain4j.dto.ChatResponse;
import com.example.langchain4j.dto.RewriteResult;
import com.example.langchain4j.event.GraphEvent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.repository.entity.ConversationSummary;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.mapper.GraphExecutionMapper;
import com.example.langchain4j.repository.mapper.SummaryMapper;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Agent 编排服务：限流 → 会话 → 锁 → 图执行 → 审计 → 响应组装。
 * <p>
 * 防御设计：
 * <ul>
 *   <li>限流（429）与会话锁（409）前置拒绝，保护下游；</li>
 *   <li>图执行整体超时，超时即中止：取消后台任务并返回降级应答，一问一答照常闭环；</li>
 *   <li>图内异常全部在节点层消化（fallback 节点保证有输出），此处只兜 graph 框架级异常；</li>
 *   <li>审计落库失败不阻断主流程（可观测性降级而非可用性降级）。</li>
 * </ul>
 */
@Slf4j
@Service
public class AgentService {

    private static final int MAX_AUDIT_JSON_LENGTH = 4000;
    /** 生成视图候选集上限：段内最近消息取这么多条参与 token 预算装配 */
    private static final int GENERATION_CANDIDATE_LIMIT = 40;

    private final CompiledGraph<WorkflowState> agentGraph;
    private final RateLimiter rateLimiter;
    private final SessionLock sessionLock;
    private final ConversationService conversationService;
    private final QueryRewriter queryRewriter;
    private final SummaryMapper summaryMapper;
    private final SummaryService summaryService;
    private final GraphExecutionMapper graphExecutionMapper;
    private final ModelRegistry modelRegistry;
    private final ThreadPoolTaskExecutor agentExecutor;
    private final AgentProperties properties;
    private final AgentMetrics metrics;

    public AgentService(CompiledGraph<WorkflowState> agentGraph,
                        RateLimiter rateLimiter,
                        SessionLock sessionLock,
                        ConversationService conversationService,
                        QueryRewriter queryRewriter,
                        SummaryMapper summaryMapper,
                        SummaryService summaryService,
                        GraphExecutionMapper graphExecutionMapper,
                        ModelRegistry modelRegistry,
                        ThreadPoolTaskExecutor agentExecutor,
                        AgentProperties properties,
                        AgentMetrics metrics) {
        this.agentGraph = agentGraph;
        this.rateLimiter = rateLimiter;
        this.sessionLock = sessionLock;
        this.conversationService = conversationService;
        this.queryRewriter = queryRewriter;
        this.summaryMapper = summaryMapper;
        this.summaryService = summaryService;
        this.graphExecutionMapper = graphExecutionMapper;
        this.modelRegistry = modelRegistry;
        this.agentExecutor = agentExecutor;
        this.properties = properties;
        this.metrics = metrics;
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
        metrics.recordAgentRequest();
        // 1. 限流（超限抛 429）
        rateLimiter.checkAcquire(request.getUserId());
        // 2. 解析本轮模型（前端指定优先，空则默认；未知模型抛 400 快速失败）
        String model = modelRegistry.resolve(request.getModel());
        // 3. 会话初始化（失败抛 DB_UNAVAILABLE，快速失败）
        String conversationId = conversationService.ensureConversation(
                request.getUserId(), request.getConversationId(), request.getQuery());
        // 4. 会话锁内：保存用户消息 → 加载历史 → 改写 → 图执行 → 保存助手消息
        //    用户与助手消息都在锁内落库，一轮对话闭环后才释放锁；
        //    否则上一轮释放锁后、下一轮先读到历史时，上一轮回答尚未落库，
        //    会造成乱序（userA → userB → assistantA）与上下文丢失。
        WorkflowState state = sessionLock.executeWithLock(conversationId, () -> {
            WorkflowState result = prepareAndInvoke(conversationId, request.getQuery(), model);
            // 助手消息持久化（结果已生成，落库失败降级不阻断响应）；
            // 超时路径在 invokeGraph 内返回降级应答，这里同样落库，保证一问一答闭环
            try {
                conversationService.saveMessage(conversationId, Message.ROLE_ASSISTANT,
                        result.result(), model, result.tokenUsage());
            } catch (Exception e) {
                log.warn("[agent] save assistant message failed, degrade: {}", e.getMessage());
            }
            return result;
        });
        if (state.fallback()) {
            metrics.recordFallback();
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

        // 手动加锁：锁跨"消息落库 + 改写 + 流消费"全过程，消费结束才释放
        AutoCloseable lock = sessionLock.lock(conversationId);
        try {
            // 锁内准备：用户消息持久化 + 改写 + 话题段归属 + 生成视图 + 摘要触发（与同步版同一流程）
            PreparedTurn turn = prepareTurn(conversationId, request.getQuery(), model);
            RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
            Map<String, Object> inputs = buildInputs(request.getQuery(), turn.rewrite().rewritten(),
                    turn.history(), turn.summary(), model);
            var nodeOutputs = agentGraph.stream(inputs, config);
            return () -> new AuditingEventIterator(conversationId, nodeOutputs.iterator(), lock);
        } catch (Exception e) {
            // 准备阶段任何异常：立即释放锁，避免锁泄漏（此前只能靠 Watchdog 兜底）
            try {
                lock.close();
            } catch (Exception closeEx) {
                log.warn("[agent] stream lock release failed: {}", closeEx.getMessage());
            }
            // 保留原异常类型（BizException 需原样抛给全局异常处理）
            if (e instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw new IllegalStateException("stream prepare failed", e);
        }
    }

    /**
     * 断点恢复：从最近 checkpoint 继续执行（无可恢复任务时返回 empty）。
     * <p>
     * 机制说明：LangGraph 的 checkpoint 是按“超级步骤”在节点执行边界落盘的
     * （每执行完一个节点保存一次完整状态）。invoke(GraphInput.resume()) 会加载
     * 该 threadId 最近一次 checkpoint，从上次中断的节点之后继续跑，而不是重头执行。
     * <p>
     * 与 chat 一致的闭环约束：
     * <ul>
     *   <li>会话锁内执行：防止恢复与新请求并发写 checkpoint/消息；</li>
     *   <li>仅当最后一轮未闭环（最新消息是 user，如进程中断）时才恢复；
     *       超时降级轮已落库 assistant，属于已闭环，不会二次恢复产生双份回答；</li>
     *   <li>恢复出的结果同样在锁内写回 assistant，保证短期记忆闭环。</li>
     * </ul>
     */
    public Optional<WorkflowState> resume(String conversationId) {
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        return sessionLock.executeWithLock(conversationId, () -> {
            // 最后一轮已闭环（含超时降级轮）：无需恢复，避免重复产生 assistant
            if (conversationService.isLastTurnClosed(conversationId)) {
                log.info("[agent] resume skipped, last turn already closed, thread={}", conversationId);
                return Optional.empty();
            }
            try {
                Optional<WorkflowState> state = agentGraph.invoke(GraphInput.resume(), config);
                // 恢复出的结果同样落库 assistant，保证续聊上下文不丢这一轮
                state.ifPresent(s -> {
                    try {
                        conversationService.saveMessage(conversationId, Message.ROLE_ASSISTANT,
                                s.result(), s.model(), s.tokenUsage());
                    } catch (Exception e) {
                        log.warn("[agent] resume save assistant message failed, degrade: {}", e.getMessage());
                    }
                });
                return state;
            } catch (Exception e) {
                log.error("[agent] resume failed, thread={}", conversationId, e);
                throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "断点恢复失败: " + rootMessage(e));
            }
        });
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

    /** 锁内准备并执行图：保存用户消息 → 双视图装配 → 改写 → 图执行（同步路径） */
    private WorkflowState prepareAndInvoke(String conversationId, String query, String model) {
        PreparedTurn turn = prepareTurn(conversationId, query, model);
        return invokeGraph(conversationId, query, turn.rewrite().rewritten(),
                turn.history(), turn.summary(), model);
    }

    /**
     * 每轮锁内准备（同步/流式共用）：用户消息持久化 → 改写视图 → 话题段归属 → 生成视图 → 触发摘要。
     * 双视图分离：改写用小窗口短截断（跨段，指代消解），生成用段内原文按 token 预算（原文回放）。
     */
    private PreparedTurn prepareTurn(String conversationId, String query, String model) {
        // 步骤1：用户消息持久化（锁内执行，失败抛 DB_UNAVAILABLE 快速失败），拿自增 id 供段起点锚定
        Message userMessage = conversationService.saveMessage(
                conversationId, Message.ROLE_USER, query, model, null);
        // 步骤2：改写视图（跨话题段取最近 N 轮，短截断），改写与话题归属合并一次 LLM 调用
        List<Map<String, String>> rewriteView = conversationService.rewriteHistoryView(
                conversationId, properties.getHistory().getRewriteWindowSize(),
                properties.getHistory().getRewriteItemMaxLength());
        // 步骤3：已有话题段 → 话题标签列表（供改写器做归属判断）
        List<ConversationSummary> segments = summaryMapper.selectByConversationId(conversationId);
        List<String> topics = segments == null ? List.of() : segments.stream()
                .map(ConversationSummary::getTopic)
                .filter(t -> t != null && !t.isBlank())
                .toList();
        // 步骤4：改写 + 话题归属（内部无历史短路/LLM 失败降级），单条审计落库便于排查改写质量
        long rewriteStart = System.currentTimeMillis();
        RewriteResult rewrite = queryRewriter.rewrite(query, rewriteView, topics, model);
        audit(conversationId, "rewrite", GraphExecution.STATUS_SUCCESS,
                query, rewrite.rewritten(), null, System.currentTimeMillis() - rewriteStart);
        // 步骤5：话题段归属：命中旧段复用（切回任务A只读A的窗口+摘要），新话题新建段
        ConversationSummary segment = resolveSegment(conversationId, segments, rewrite, userMessage.getId());
        // 步骤6：生成视图（当前段原文，token 预算装配，排除当前消息）
        List<Map<String, String>> generationView = conversationService.generationHistoryView(
                conversationId, segment.getStartMessageId(),
                properties.getHistory().getGenerationMaxTokens(),
                properties.getHistory().getGenerationItemMaxLength(),
                GENERATION_CANDIDATE_LIMIT);
        // 步骤7：异步触发段摘要（锁内仅提交任务，不等待 LLM 执行）
        summaryService.triggerIfNeeded(conversationId, segment);
        return new PreparedTurn(rewrite, generationView,
                segment.getContent() == null ? "" : segment.getContent());
    }

    /**
     * 话题段归属：改写器 topic 精确命中已有段 → 复用该段；最新段未命名且改写器给出
     * 话题名 → 复用并补名；newTopic → 新建段；未判断/解析降级 → 兜底最新段。
     * 复用旧段时生成视图与摘要自动只取该段内容。
     */
    private ConversationSummary resolveSegment(String conversationId, List<ConversationSummary> segments,
                                               RewriteResult rewrite, Long userMessageId) {
        String topic = rewrite.topic();
        // 1. 无段：创建第一段（首轮话题，topic 可能为空）
        if (segments == null || segments.isEmpty()) {
            return createSegment(conversationId, 1, topic, userMessageId);
        }
        // 2. topic 精确命中已有段 → 复用（用户切回旧话题）
        if (topic != null && !topic.isBlank()) {
            for (ConversationSummary s : segments) {
                if (topic.equals(s.getTopic())) {
                    log.info("[agent] switch back to topic segment, thread={}, segment={}, topic={}",
                            conversationId, s.getSegmentId(), topic);
                    return s;
                }
            }
        }
        // 3. 最新段尚未命名且改写器给出话题名 → 复用最新段并补名
        //    （首轮创建的段 topic 为空，补名后首轮内容纳入该段，不游离在段外）
        ConversationSummary latest = segments.get(segments.size() - 1);
        if (topic != null && !topic.isBlank()
                && (latest.getTopic() == null || latest.getTopic().isBlank())) {
            latest.setTopic(topic);
            summaryMapper.upsertIncrement(latest);
            log.info("[agent] name latest segment, thread={}, segment={}, topic={}",
                    conversationId, latest.getSegmentId(), topic);
            return latest;
        }
        // 4. 改写器判定为新话题 → 新建段（话题漂移）
        if (rewrite.newTopic()) {
            int next = segments.stream()
                    .mapToInt(s -> s.getSegmentId() == null ? 0 : s.getSegmentId())
                    .max().orElse(0) + 1;
            return createSegment(conversationId, next, topic, userMessageId);
        }
        // 5. 兜底：归属最新段（segmentId 最大）
        return latest;
    }

    /** 新建话题段：start_message_id 锚定当前用户消息，content 为空等摘要任务异步填充 */
    private ConversationSummary createSegment(String conversationId, int segmentId, String topic, Long startMessageId) {
        ConversationSummary summary = new ConversationSummary();
        summary.setConversationId(conversationId);
        summary.setSegmentId(segmentId);
        summary.setTopic(topic == null ? "" : topic);
        summary.setContent("");
        summary.setStartMessageId(startMessageId);
        summaryMapper.insert(summary);
        log.info("[agent] new topic segment created, thread={}, segment={}, topic={}",
                conversationId, segmentId, topic);
        return summary;
    }

    /** 每轮准备产物：改写结果 + 生成视图 + 当前段摘要，供同步/流式共用 */
    private record PreparedTurn(RewriteResult rewrite, List<Map<String, String>> history, String summary) {
    }

    private Map<String, Object> buildInputs(String query, String rewritten,
                                            List<Map<String, String>> history, String summary, String model) {
        return Map.of(
                WorkflowState.QUERY, query,
                WorkflowState.MODEL, model,
                WorkflowState.REWRITTEN_QUERY, rewritten == null ? "" : rewritten,
                WorkflowState.HISTORY, history == null ? List.of() : history,
                WorkflowState.SUMMARY, summary == null ? "" : summary);
    }

    private WorkflowState invokeGraph(String conversationId, String query, String rewritten,
                                      List<Map<String, String>> history, String summary, String model) {
        long start = System.currentTimeMillis();
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        Map<String, Object> inputs = buildInputs(query, rewritten, history, summary, model);
        long timeoutSeconds = Math.max(10, properties.getExecutionTimeoutSeconds());
        // 图执行放入专用线程池，HTTP 线程只等待结果。
        // 超时即中止：future.cancel(true) 会中断工作线程（尽力而为，Java 无法强制杀死线程），
        // 本轮改为返回降级应答（user + assistant 照常落库），一问一答闭环，用户可直接重发。
        Future<WorkflowState> future;
        try {
            future = agentExecutor.getThreadPoolExecutor().submit(() -> {
                try {
                    return agentGraph.invoke(inputs, config).orElse(null);
                } catch (Exception e) {
                    throw e;
                }
            });
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
            metrics.recordGraphDuration(System.currentTimeMillis() - start);
            audit(conversationId, "workflow", GraphExecution.STATUS_SUCCESS,
                    query, state.result(), null, null);
            return state;
        } catch (TimeoutException e) {
            // 超时即中止：取消后台任务（中断工作线程，尽力而为）。
            // cancel 返回 false 说明任务在超时点恰好刚完成，直接取回结果，不浪费本轮执行。
            if (future.cancel(true)) {
                // 超时即中止：取消后台任务，返回降级应答而非报错 ——
                // 本轮 user + 降级 assistant 照常落库，历史一问一答完整，用户可直接重发。
                audit(conversationId, "workflow", GraphExecution.STATUS_FAILED,
                        query, null, "执行超时已中止", null);
                log.warn("[agent] execution timeout after {}s, thread={}, task cancelled, return fallback answer",
                        timeoutSeconds, conversationId);
                return new WorkflowState(Map.of(
                        WorkflowState.RESULT, PromptTemplates.FALLBACK_TIMEOUT_ANSWER,
                        WorkflowState.FALLBACK, true,
                        WorkflowState.DONE, true));
            }
            try {
                WorkflowState completed = future.get();
                if (completed == null) {
                    throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行未返回结果");
                }
                log.info("[agent] execution completed just after timeout, thread={}", conversationId);
                metrics.recordGraphDuration(System.currentTimeMillis() - start);
                audit(conversationId, "workflow", GraphExecution.STATUS_SUCCESS,
                        query, completed.result(), null, null);
                return completed;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                if (cause instanceof BizException biz) {
                    throw biz;
                }
                log.error("[agent] graph execution failed, thread={}", conversationId, cause);
                audit(conversationId, "workflow", GraphExecution.STATUS_FAILED, query, null, rootMessage(cause), null);
                throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行失败: " + rootMessage(cause));
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new BizException(ErrorCode.AGENT_TIMEOUT);
            }
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
