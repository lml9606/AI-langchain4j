package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.ChatRequest;
import com.example.langchain4j.dto.ChatResponse;
import com.example.langchain4j.graph.GraphEvent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.guard.SessionLock;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.repository.entity.ConversationSummary;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.repository.entity.Message;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;
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

    /** 生成视图候选集上限：段内最近消息取这么多条参与 token 预算装配 */
    private static final int GENERATION_CANDIDATE_LIMIT = 40;

    private final CompiledGraph<WorkflowState> agentGraph;
    private final RateLimiter rateLimiter;
    private final SessionLock sessionLock;
    private final ConversationService conversationService;
    private final QueryRewriter queryRewriter;
    private final SegmentService segmentService;
    private final AuditService auditService;
    private final SummaryService summaryService;
    private final ModelRegistry modelRegistry;
    private final ThreadPoolTaskExecutor agentExecutor;
    private final AgentProperties properties;
    private final AgentMetrics metrics;

    public AgentService(CompiledGraph<WorkflowState> agentGraph,
                        RateLimiter rateLimiter,
                        SessionLock sessionLock,
                        ConversationService conversationService,
                        QueryRewriter queryRewriter,
                        SegmentService segmentService,
                        AuditService auditService,
                        SummaryService summaryService,
                        ModelRegistry modelRegistry,
                        ThreadPoolTaskExecutor agentExecutor,
                        AgentProperties properties,
                        AgentMetrics metrics) {
        this.agentGraph = agentGraph;
        this.rateLimiter = rateLimiter;
        this.sessionLock = sessionLock;
        this.conversationService = conversationService;
        this.queryRewriter = queryRewriter;
        this.segmentService = segmentService;
        this.auditService = auditService;
        this.summaryService = summaryService;
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
        // 2. 输入规整：控制字符清洗 + trim（Controller @Valid 拦空/超长，这里堵“全是控制字符”的漏网输入）
        String query = normalizeQuery(request.getQuery());
        // 3. 解析本轮模型（前端指定优先，空则默认；未知模型抛 400 快速失败）
        String model = modelRegistry.resolve(request.getModel());
        // 4. 会话初始化（失败抛 DB_UNAVAILABLE，快速失败）
        String conversationId = conversationService.ensureConversation(
                request.getUserId(), request.getConversationId(), query);
        // 5. 会话锁内：保存用户消息 → 装配记忆 → 图执行 → 保存助手消息
        //    用户与助手消息都在锁内落库，一轮对话闭环后才释放锁；
        //    否则上一轮释放锁后、下一轮先读到历史时，上一轮回答尚未落库，
        //    会造成乱序（userA → userB → assistantA）与上下文丢失。
        WorkflowState state = sessionLock.executeWithLock(conversationId, () -> {
            WorkflowState result = prepareAndInvoke(conversationId, query, model);
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
                state.tokenUsage(), System.currentTimeMillis() - start, state.done(), state.rootCause());
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
        // 输入规整（与同步版同一语义：堵“全是控制字符”的漏网输入，全链路用清洗后的 query）
        String query = normalizeQuery(request.getQuery());
        String model = modelRegistry.resolve(request.getModel());
        String conversationId = conversationService.ensureConversation(
                request.getUserId(), request.getConversationId(), query);

        // 手动加锁：锁跨"消息落库 + 改写 + 流消费"全过程，消费结束才释放
        AutoCloseable lock = sessionLock.lock(conversationId);
        try {
            // 锁内准备：用户消息持久化 + 改写 + 话题段归属 + 生成视图 + 摘要触发（与同步版同一流程）
            PreparedTurn turn = prepareTurn(conversationId, query, model);
            RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
            Map<String, Object> inputs = buildInputs(query, turn.rewrite().rewritten(),
                    turn.history(), turn.summary(), model);
            var nodeOutputs = agentGraph.stream(inputs, config);
            return () -> new AuditingEventIterator(conversationId, nodeOutputs.iterator(), lock, auditService);
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

    /**
     * 入口输入规整：剔除控制字符 + 去首尾空白，清洗后为空抛 400。
     * <p>
     * 与 Controller @Valid 的分工：@Valid 拦空串与超长（4000），这里堵
     * “全是控制字符”的漏网输入，并保证存库/改写/入图全链路使用同一份清洗后的 query。
     * QueryRewriter 与 EntryNode 内的清洗保留为兜底（幂等，再洗一遍无害）。
     *
     * @param query 原始用户输入（可为 null）
     * @return 清洗后的输入；清洗后为空抛 {@link BizException}（BAD_REQUEST）
     */
    private String normalizeQuery(String query) {
        String cleaned = query == null ? "" : query.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (cleaned.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "输入内容清洗后为空，请检查后重试");
        }
        return cleaned;
    }

    /**
     * 锁内准备并执行图（同步路径）：保存用户消息 → 双视图装配 → 改写 → 图执行。
     *
     * @param conversationId 会话 ID（threadId）
     * @param query          原始用户输入
     * @param model          本轮模型名（已解析）
     * @return 图执行最终状态
     */
    private WorkflowState prepareAndInvoke(String conversationId, String query, String model) {
        PreparedTurn turn = prepareTurn(conversationId, query, model);
        return invokeGraph(conversationId, query, turn.rewrite().rewritten(),
                turn.history(), turn.summary(), model);
    }

    /**
     * 每轮锁内准备（同步/流式共用），装配两条独立的历史视图：
     * <ul>
     *   <li>改写视图（跨话题最近 N 条）→ 【只给改写器】：指代消解 + 话题判断；</li>
     *   <li>生成视图（当前话题段内原文）→ 【只给生成 LLM】：原文回放，其他话题不带。</li>
     * </ul>
     *
     * @param conversationId 会话 ID（threadId）
     * @param query          原始用户输入（落库与改写输入）
     * @param model          本轮模型名（改写调用与消息落库共用）
     * @return 每轮准备产物（改写结果 + 生成视图 + 当前段摘要）
     */
    private PreparedTurn prepareTurn(String conversationId, String query, String model) {
        // 步骤1：存用户消息（段号待步骤5.5回填），拿自增 id 供话题段起点锚定
        Message userMessage = conversationService.saveMessage(
                conversationId, Message.ROLE_USER, query, model, null);
        // 步骤2：改写视图（跨话题最近 N 条，只给改写器，不进生成）
        List<Map<String, String>> rewriteView = conversationService.rewriteHistoryView(
                conversationId, properties.getHistory().getRewriteWindowSize(),
                properties.getHistory().getRewriteItemMaxLength());
        // 步骤3：已有话题段 → 话题标签列表（供改写器做归属判断）
        List<ConversationSummary> segments = segmentService.listSegments(conversationId);
        List<String> topics = segments.stream()
                .map(ConversationSummary::getTopic)
                .filter(t -> t != null && !t.isBlank())
                .toList();
        // 步骤4：改写 + 话题归属（一次 LLM 调用；内部无历史短路/LLM 失败降级），审计落库便于排查
        long rewriteStart = System.currentTimeMillis();
        RewriteResult rewrite = queryRewriter.rewrite(query, rewriteView, topics, model);
        auditService.audit(conversationId, "rewrite", GraphExecution.STATUS_SUCCESS,
                query, rewrite.rewritten(), null, System.currentTimeMillis() - rewriteStart);
        // 步骤5：话题段归属：命中旧段复用（切回任务A只读A的窗口+摘要），新话题新建段
        ConversationSummary segment = segmentService.resolveSegment(conversationId, segments, rewrite, userMessage.getId());
        // 步骤5.5：回填本轮 user 消息的段归属（assistant 落库时继承），生成视图按段号精确过滤防混段
        conversationService.updateMessageSegment(userMessage.getId(), segment.getSegmentId());
        // 步骤6：生成视图（当前话题段内原文，只给生成 LLM；token 预算装配，排除当前消息）
        List<Map<String, String>> generationView = conversationService.generationHistoryView(
                conversationId, segment.getSegmentId(),
                properties.getHistory().getGenerationMaxTokens(),
                properties.getHistory().getGenerationItemMaxLength(),
                GENERATION_CANDIDATE_LIMIT);
        // 步骤7：异步触发段摘要（锁内仅提交任务，不等待 LLM 执行）
        summaryService.triggerIfNeeded(conversationId, segment);
        return new PreparedTurn(rewrite, generationView,
                segment.getContent() == null ? "" : segment.getContent());
    }

    /** 每轮准备产物：改写结果 + 生成视图 + 当前段摘要，供同步/流式共用 */
    private record PreparedTurn(RewriteResult rewrite, List<Map<String, String>> history, String summary) {
    }

    /**
     * 组装图输入：query / 模型名 / 改写句 / 生成视图 / 段摘要（空值兜底为空串或空列表）。
     *
     * @param query     原始用户输入
     * @param rewritten 改写后的自包含句
     * @param history   生成视图（当前话题段内原文，role/content 键值对）
     * @param summary   当前话题段摘要（可为空）
     * @param model     本轮模型名
     * @return 图输入键值对（key 为 WorkflowState 字段名常量）
     */
    private Map<String, Object> buildInputs(String query, String rewritten,
                                            List<Map<String, String>> history, String summary, String model) {
        return Map.of(
                WorkflowState.QUERY, query,
                WorkflowState.MODEL, model,
                WorkflowState.REWRITTEN_QUERY, rewritten == null ? "" : rewritten,
                WorkflowState.HISTORY, history == null ? List.of() : history,
                WorkflowState.SUMMARY, summary == null ? "" : summary);
    }

    /**
     * 图执行（同步路径）：提交专用线程池 + 整体超时中止 + 异常转换为业务错误码。
     * <p>
     * 超时策略：future.cancel(true) 尽力中断工作线程，本轮改返回降级应答
     * （user + assistant 照常落库），一问一答闭环；cancel 返回 false 说明任务恰在
     * 超时点完成，直接取回结果，不浪费本轮执行。
     *
     * @param conversationId 会话 ID（threadId，checkpoint 隔离维度）
     * @param query          原始用户输入（审计用）
     * @param rewritten      改写后的自包含句（图内实际使用）
     * @param history        生成视图（当前话题段内原文，role/content 键值对）
     * @param summary        当前话题段摘要（可为空）
     * @param model          本轮模型名（已解析）
     * @return 图执行最终状态；超时返回降级 WorkflowState（fallback=true）
     */
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
                auditService.audit(conversationId, "workflow", GraphExecution.STATUS_FAILED,
                        query, null, "图执行未返回状态", null);
                throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行未返回结果");
            }
            metrics.recordGraphDuration(System.currentTimeMillis() - start);
            auditService.audit(conversationId, "workflow", GraphExecution.STATUS_SUCCESS,
                    query, state.result(), null, null, state.rootCause());
            return state;
        } catch (TimeoutException e) {
            // 超时即中止：取消后台任务（中断工作线程，尽力而为）。
            // cancel 返回 false 说明任务在超时点恰好刚完成，直接取回结果，不浪费本轮执行。
            if (future.cancel(true)) {
                // 超时即中止：取消后台任务，返回降级应答而非报错 ——
                // 本轮 user + 降级 assistant 照常落库，历史一问一答完整，用户可直接重发。
                auditService.audit(conversationId, "workflow", GraphExecution.STATUS_FAILED,
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
                auditService.audit(conversationId, "workflow", GraphExecution.STATUS_SUCCESS,
                        query, completed.result(), null, null, completed.rootCause());
                return completed;
            } catch (ExecutionException ex) {
                Throwable cause = ex.getCause() == null ? ex : ex.getCause();
                if (cause instanceof BizException biz) {
                    throw biz;
                }
                log.error("[agent] graph execution failed, thread={}", conversationId, cause);
                auditService.audit(conversationId, "workflow", GraphExecution.STATUS_FAILED, query, null, rootMessage(cause), null);
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
            auditService.audit(conversationId, "workflow", GraphExecution.STATUS_FAILED, query, null, rootMessage(cause), null);
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行失败: " + rootMessage(cause));
        }
    }

    /** 取异常链最深处的 message（业务原因通常在最底层），避免向上只看到包装信息 */
    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }
}
