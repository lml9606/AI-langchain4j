package com.example.langchain4j.agent;

import com.example.langchain4j.agent.dto.BusinessResult;
import com.example.langchain4j.agent.dto.ChatRequest;
import com.example.langchain4j.agent.dto.ChatResponse;
import com.example.langchain4j.agent.dto.ConfirmRequest;
import com.example.langchain4j.agent.stream.AgentStreamEvent;
import com.example.langchain4j.agent.stream.AgentStreamSink;
import com.example.langchain4j.auth.security.UserContext;
import com.example.langchain4j.business.ticket.TicketService;
import com.example.langchain4j.common.concurrent.BoundedCall;
import com.example.langchain4j.common.constant.ChatOptions;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.common.util.Throwables;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.conversation.ConversationService;
import com.example.langchain4j.conversation.ConversationSummary;
import com.example.langchain4j.conversation.Message;
import com.example.langchain4j.conversation.model.RewriteResult;
import com.example.langchain4j.conversation.QueryRewriter;
import com.example.langchain4j.conversation.SegmentService;
import com.example.langchain4j.conversation.SummaryService;
import com.example.langchain4j.graph.qa.model.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.model.TaskOperation;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.guard.SessionLock;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.memory.UserMemoryService;
import com.example.langchain4j.prompt.PromptTemplates;
import com.example.langchain4j.prompt.PromptTemplateService;
import com.example.langchain4j.tool.confirmation.ConfirmationCancellationService;
import com.example.langchain4j.tool.confirmation.ConfirmationService;
import com.example.langchain4j.tool.confirmation.TaskConfirmService;
import com.example.langchain4j.tool.execution.ToolExecution;
import com.example.langchain4j.tool.execution.ToolExecutionService;
import com.example.langchain4j.tool.run.TaskRun;
import com.example.langchain4j.tool.run.TaskRunService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.NodeOutput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Agent 编排服务：限流、会话、会话锁、图执行和响应组装。
 *
 * 限流（429）与会话锁（409）在进入下游前拒绝；对话图有整体超时，超时后取消后台任务并
 * 返回降级应答；确认写链路超时则返回“执行中”，以 ai_tool_execution 台账作为执行事实源。
 * 图内异常由节点层消化，这里只兜住 graph 框架级异常。
 */
@Slf4j
@Service
public class AgentService {

    private final CompiledGraph<WorkflowState> agentGraph;
    private final TaskConfirmService taskConfirmService;
    private final RateLimiter rateLimiter;
    private final SessionLock sessionLock;
    private final ConversationService conversationService;
    private final QueryRewriter queryRewriter;
    private final SegmentService segmentService;
    private final SummaryService summaryService;
    private final ModelRegistry modelRegistry;
    private final TicketService ticketService;
    private final ThreadPoolTaskExecutor agentExecutor;
    private final AgentProperties properties;
    private final AgentMetrics metrics;
    private final PromptTemplateService promptTemplateService;
    private final ConfirmationService confirmationService;
    private final ConfirmationCancellationService confirmationCancellationService;
    private final ToolExecutionService toolExecutionService;
    private final UserMemoryService userMemoryService;
    private final TaskRunService taskRunService;

    /**
     * 注入图执行、会话、限流、模型与确认等编排依赖。
     */
    public AgentService(CompiledGraph<WorkflowState> agentGraph,
                        TaskConfirmService taskConfirmService,
                        RateLimiter rateLimiter, SessionLock sessionLock,
                        ConversationService conversationService, QueryRewriter queryRewriter,
                        SegmentService segmentService,
                        SummaryService summaryService, ModelRegistry modelRegistry,
                        TicketService ticketService, ThreadPoolTaskExecutor agentExecutor,
                        AgentProperties properties, AgentMetrics metrics,
                        PromptTemplateService promptTemplateService,
                        ConfirmationService confirmationService,
                        ConfirmationCancellationService confirmationCancellationService,
                        ToolExecutionService toolExecutionService,
                        UserMemoryService userMemoryService,
                        TaskRunService taskRunService) {
        this.agentGraph = agentGraph;
        this.taskConfirmService = taskConfirmService;
        this.rateLimiter = rateLimiter;
        this.sessionLock = sessionLock;
        this.conversationService = conversationService;
        this.queryRewriter = queryRewriter;
        this.segmentService = segmentService;
        this.summaryService = summaryService;
        this.modelRegistry = modelRegistry;
        this.ticketService = ticketService;
        this.agentExecutor = agentExecutor;
        this.properties = properties;
        this.metrics = metrics;
        this.promptTemplateService = promptTemplateService;
        this.confirmationService = confirmationService;
        this.confirmationCancellationService = confirmationCancellationService;
        this.toolExecutionService = toolExecutionService;
        this.userMemoryService = userMemoryService;
        this.taskRunService = taskRunService;
    }

    /**
     * 同步对话：在会话锁内完成用户消息落库、上下文装配、图执行和助手消息落库。
     *
     * conversationId 同时作为 LangGraph 的 threadId，checkpoint 按 threadId 隔离存储，
     * 因此一个会话对应一条 checkpoint 历史，续聊或断点恢复时可以直接找回对应状态。
     * 限流、输入清洗、模型解析和会话初始化在锁外完成；图执行异常和整体超时由后续调用统一处理。
     *
     * 返回本轮最终响应；输入非法、限流、会话锁冲突或执行失败时抛出对应的业务异常。
     */
    public ChatResponse chat(ChatRequest request) {
        long start = System.currentTimeMillis();
        metrics.recordAgentRequest();
        // 身份以 JWT 为准（JwtAuthFilter 已解析写入上下文，登录态不可伪造）
        String userId = UserContext.require();
        // 1. 限流（超限抛 429）
        rateLimiter.checkAcquire(userId);
        // 2. 输入规整：控制字符清洗 + trim（Controller @Valid 拦空/超长，这里堵“全是控制字符”的漏网输入）
        String query = normalizeQuery(request.getQuery());
        // 3. 解析本轮模型（前端指定优先，空则默认；未知模型抛 400 快速失败）
        String model = modelRegistry.resolve(request.getModel());
        // 3.5 回答模式与检索可见范围归一（白名单外回默认；入图前收敛，图内不再校验）
        String answerMode = normalizeAnswerMode(request.getAnswerMode());
        String visibility = normalizeVisibility(request.getVisibility());
        // 4. 会话初始化（失败抛 DB_UNAVAILABLE，快速失败）
        String conversationId =
                conversationService.ensureConversation(userId, request.getConversationId(), query);
        log.info("[agent] chat start, conversation={}, model={}, queryLength={}, answerMode={}, " +
                "visibility={}", conversationId, model, query.length(), answerMode, visibility);
        // 5. 会话锁内：保存用户消息 → 装配记忆 → 图执行 → 保存助手消息
        //    用户与助手消息都在锁内落库，一轮对话闭环后才释放锁；
        //    否则上一轮释放锁后、下一轮先读到历史时，上一轮回答尚未落库，
        //    会造成乱序（userA → userB → assistantA）与上下文丢失。
        WorkflowState state = sessionLock.executeWithLock(conversationId, () -> {
            // 新用户消息视为放弃旧确认：数据库先取消并提交，最后才清理 Redis 令牌。
            confirmationCancellationService.cancelByNewMessage(conversationId, userId);
            WorkflowState result =
                    prepareAndInvoke(conversationId, userId, query, model, answerMode, visibility);
            // 助手消息持久化（结果已生成，落库失败降级不阻断响应）；
            // 超时路径在 invokeGraph 内返回降级应答，这里同样落库，保证一问一答闭环
            // context 快照：改写视图锚定检索资料原文而非生成答案，防追问指代带偏
            try {
                conversationService.saveMessage(conversationId, Message.ROLE_ASSISTANT,
                        result.result(), model, result.tokenUsage(),
                        snapshotContext(result.context()));
            } catch (Exception e) {
                log.warn("[agent] save assistant message failed, degrade: {}", e.getMessage());
            }
            return result;
        });
        if (state.fallback()) {
            metrics.recordFallback();
        }
        // 转人工轮自动建单（运营闭环；失败降级不阻断响应）
        if (state.handoff()) {
            ticketService.createIfAbsent(userId, conversationId, query, state.rootCause());
        }
        // 长期记忆异步抽取：只提交任务，不阻塞本轮响应；失败不影响对话
        if (!state.fallback()) {
            userMemoryService.extractAsync(userId, conversationId, query, state.result());
        }
        log.info("[agent] chat done, conversation={}, intent={}, fallback={}, handoff={}, " +
                        "costMs={}, resultLength={}, tokenUsage={}, toolCalls={}, rootCause={}",
                conversationId, state.intent(), state.fallback(), state.handoff(),
                System.currentTimeMillis() - start, resultLength(state.result()),
                state.tokenUsage(), state.taskTrace().size(), state.rootCause());
        return buildChatResponse(conversationId, state, start);
    }

    /**
     * 流式对话：与同步 chat 共用输入校验、限流、会话锁、消息落库和响应组装，
     * 差别只在图执行阶段把节点进度实时推给 sink，最终结果通过 done 事件返回。
     *
     * 事件顺序：meta → node（0..N）→ done；失败时以 error 结束。
     * 客户端断开（sink 返回 false）立即停止图执行，不落助手消息，
     * 避免把半成品答案写进历史导致下一轮上下文错乱。
     *
     * @param request 对话请求（字段校验与同步 chat 一致）
     * @param sink    SSE 事件接收端；返回 false 表示客户端已断开
     */
    public void stream(ChatRequest request, AgentStreamSink sink) {
        long start = System.currentTimeMillis();
        metrics.recordAgentRequest();
        // 身份、限流、输入清洗与同步链路完全一致，避免两套入口规则漂移
        String userId = UserContext.require();
        rateLimiter.checkAcquire(userId);
        String query = normalizeQuery(request.getQuery());
        String model = modelRegistry.resolve(request.getModel());
        String answerMode = normalizeAnswerMode(request.getAnswerMode());
        String visibility = normalizeVisibility(request.getVisibility());
        String conversationId =
                conversationService.ensureConversation(userId, request.getConversationId(), query);
        log.info("[agent] stream start, conversation={}, model={}, queryLength={}, answerMode={}, "
                        + "visibility={}", conversationId, model, query.length(), answerMode,
                visibility);

        // 先发送元信息：前端拿到 conversationId 后即可续聊和渲染本轮标题
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("conversationId", conversationId);
        meta.put("model", model);
        meta.put("answerMode", answerMode);
        meta.put("visibility", visibility);
        if (!sink.emit(AgentStreamEvent.of("meta", meta))) {
            log.info("[agent] stream cancelled before graph, conversation={}", conversationId);
            return;
        }

        WorkflowState state = sessionLock.executeWithLock(conversationId, () -> {
            confirmationCancellationService.cancelByNewMessage(conversationId, userId);
            PreparedTurn turn = prepareTurn(conversationId, userId, query, model);
            RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
            Map<String, Object> inputs = buildInputs(query, turn.rewrite().rewritten(),
                    turn.history(), turn.summary(), turn.memory(), model, userId, conversationId,
                    answerMode, visibility);
            WorkflowState result = executeGraphWithStream(inputs, config, conversationId, start,
                    sink);
            if (result == null) {
                // 客户端中途断开：本轮不写助手消息；用户重发时按新问题处理
                return null;
            }
            try {
                conversationService.saveMessage(conversationId, Message.ROLE_ASSISTANT,
                        result.result(), model, result.tokenUsage(),
                        snapshotContext(result.context()));
            } catch (Exception e) {
                log.warn("[agent] stream save assistant message failed, degrade: {}",
                        e.getMessage());
            }
            return result;
        });

        if (state == null) {
            log.info("[agent] stream cancelled mid-graph, conversation={}, costMs={}",
                    conversationId, System.currentTimeMillis() - start);
            return;
        }
        if (state.fallback()) {
            metrics.recordFallback();
        }
        if (state.handoff()) {
            ticketService.createIfAbsent(userId, conversationId, query, state.rootCause());
        }
        // 流式轮同样异步沉淀长期记忆；只在正常回答时抽取，避免把兜底文案当成事实
        if (!state.fallback()) {
            userMemoryService.extractAsync(userId, conversationId, query, state.result());
        }
        ChatResponse response = buildChatResponse(conversationId, state, start);
        log.info("[agent] stream done, conversation={}, intent={}, fallback={}, handoff={}, "
                        + "costMs={}, resultLength={}, tokenUsage={}",
                conversationId, state.intent(), state.fallback(), state.handoff(),
                System.currentTimeMillis() - start, resultLength(state.result()),
                state.tokenUsage());
        sink.emit(AgentStreamEvent.of("done", response));
    }

    /**
     * 确认写操作：令牌只做短期授权，执行状态由数据库台账兜底。
     *
     * 入口先做限流和会话归属校验，然后在严格会话锁内只读取一次令牌。读取时使用
     * Watchdog 自动续期的会话锁，Redis 故障按 fail-closed 处理，避免确认动作脱离锁执行。
     * 锁定后读取令牌；令牌不存在时按执行台账幂等重放。台账正在执行返回 409，
     * 已成功或已取消返回既有结果；只有台账允许时才进入确认执行或取消。
     *
     * 用户同意后，写工具会和执行台账在同一个数据库事务内完成；成功删除令牌，失败或
     * 没有台账时保留令牌，允许用户用同一个 confirmationId 重试。重复点击由令牌和
     * 台账幂等键共同拦截，不会重复写业务数据。
     */
    public ChatResponse confirm(ConfirmRequest request) {
        long start = System.currentTimeMillis();
        metrics.recordAgentRequest();
        String userId = UserContext.require();
        rateLimiter.checkAcquire(userId);

        String conversationId = request.getConversationId();
        if (conversationId == null || conversationId.isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "conversationId 不能为空");
        }
        conversationService.requireOwnership(conversationId, userId);

        // 令牌读取放在严格会话锁内且只读一次：锁外只做会话归属校验，避免并发确认读到不同快照。
        return sessionLock.executeWithLockStrict(conversationId, () -> {
            Optional<ConfirmationService.PendingAction> current =
                    confirmationService.preview(conversationId, request.getConfirmationId(),
                            userId);
            if (current.isEmpty()) {
                return replayFinished(conversationId, request.getConfirmationId(), userId, start);
            }
            ConfirmationService.PendingAction action = current.get();
            // 先看多步运行台账：RUNNING 拒绝并发执行，已成功/已取消直接按幂等重放
            TaskRun run = taskRunService.find(userId, action.confirmationId()).orElse(null);
            if (run != null) {
                if (run.getStatus() == TaskRun.Status.RUNNING) {
                    // 只有超过 staleSeconds 的 RUNNING 才允许接管；否则视为另一实例仍在执行
                    int staleSeconds = Math.max(1,
                            properties.getTool().getConfirmation().getStaleSeconds());
                    boolean stale = run.getUpdatedAt() == null
                            || run.getUpdatedAt().isBefore(
                            OffsetDateTime.now().minusSeconds(staleSeconds));
                    if (!stale) {
                        throw new BizException(ErrorCode.CONFIRMATION_IN_PROGRESS);
                    }
                    log.warn("[agent] stale run takeover, conversation={}, runTail={}, " +
                                    "statusUpdatedAt={}", conversationId,
                            idTail(action.confirmationId()), run.getUpdatedAt());
                }
                if (run.getStatus() == TaskRun.Status.SUCCEEDED
                        || run.getStatus() == TaskRun.Status.CANCELLED) {
                    confirmationService.finish(action.conversationId(), action.confirmationId());
                    return replayFinished(conversationId, request.getConfirmationId(), userId,
                            start);
                }
            } else {
                // 兼容升级前创建的单步令牌（executionId = confirmationId，没有 run 记录）
                ToolExecution execution = legacyExecution(userId, action.confirmationId());
                if (execution != null && execution.getStatus() == ToolExecution.Status.RUNNING) {
                    throw new BizException(ErrorCode.CONFIRMATION_IN_PROGRESS);
                }
                if (execution != null && execution.getStatus() == ToolExecution.Status.SUCCEEDED) {
                    confirmationService.finish(action.conversationId(), action.confirmationId());
                    return replayFinished(conversationId, request.getConfirmationId(), userId,
                            start);
                }
                if (execution != null && execution.getStatus() == ToolExecution.Status.CANCELLED) {
                    log.info("[agent] confirm blocked by cancelled ledger, conversation={}, "
                                    + "confirmationTail={}", conversationId,
                            idTail(action.confirmationId()));
                    confirmationService.finish(action.conversationId(), action.confirmationId());
                    return replayFinished(conversationId, request.getConfirmationId(), userId,
                            start);
                }
            }
            log.info("[agent] confirm start, conversation={}, confirmationTail={}, approved={}, " +
                            "steps={}, firstTool={}", conversationId, idTail(action.confirmationId()),
                    Boolean.TRUE.equals(request.getApproved()), action.steps().size(), action.tool());
            return Boolean.TRUE.equals(request.getApproved()) ? executeConfirmed(action, userId, start) :
                    rejectConfirmed(action, start);
        });
    }

    /**
     * 取消路径：只记录取消消息，不触碰业务数据；完成后删除令牌
     */
    private ChatResponse rejectConfirmed(ConfirmationService.PendingAction action, long start) {
        String answer = "已取消该操作，未对业务数据做任何修改。";
        conversationService.saveMessage(action.conversationId(), Message.ROLE_USER,
                "取消确认：" + action.summary(), action.model(), null);
        conversationService.saveMessage(action.conversationId(), Message.ROLE_ASSISTANT, answer,
                action.model(), null);
        try {
            toolExecutionService.cancelByRun(action.userId(), action.confirmationId());
            toolExecutionService.markCancelled(action.userId(), action.confirmationId());
            taskRunService.markCancelled(action.userId(), action.confirmationId());
        } catch (RuntimeException e) {
            // 台账是执行事实源，但 Redis 令牌已足以完成取消；这里降级告警，不阻断用户取消。
            log.warn("[agent] cancel ledger update failed, conversation={}, confirmationTail={}, "
                            + "reason={}", action.conversationId(), idTail(action.confirmationId()),
                    e.getMessage());
        }
        confirmationService.finish(action.conversationId(), action.confirmationId());
        log.info("[agent] confirm rejected, conversation={}, confirmationTail={}, costMs={}",
                action.conversationId(), idTail(action.confirmationId()),
                System.currentTimeMillis() - start);
        ChatResponse response = ChatResponse.of(action.conversationId(), answer, false, null,
                System.currentTimeMillis() - start, true, "");
        response.setBusiness(BusinessResult.failure("CANCELLED", "用户已取消写操作"));
        return response;
    }

    /**
     * 执行路径：执行已确认的单个写工具，并以数据库台账判定成败。
     *
     * 成功（台账 SUCCEEDED）删除令牌；失败或没有台账时保留令牌，
     * 用户可以用同一个 confirmationId 重试。重复执行由台账唯一键拦截。
     */
    private ChatResponse executeConfirmed(ConfirmationService.PendingAction action, String userId,
                                          long start) {
        String confirmQuery = "确认执行：" + action.summary();
        try {
            conversationService.saveMessage(action.conversationId(), Message.ROLE_USER,
                    confirmQuery, action.model(), null);
            WorkflowState state = invokeTaskConfirm(action, userId, confirmQuery, start);
            // 写操作成败以工具执行台账为准，不能用“图正常返回”冒充业务成功。
            TaskRun run = taskRunService.find(userId, action.confirmationId()).orElse(null);
            ToolExecution execution = run == null
                    ? legacyExecution(userId, action.confirmationId())
                    : null;
            boolean succeeded = run != null
                    ? run.getStatus() == TaskRun.Status.SUCCEEDED
                    : execution != null && execution.getStatus() == ToolExecution.Status.SUCCEEDED;
            // 成败以台账为准：工具失败但确认执行链路正常返回时，不能把令牌当成已消费
            if (succeeded) {
                // 令牌先完成：即使后续消息落库失败，客户端用同一 ID 重试也只会拿到台账里的同一结果
                confirmationService.finish(action.conversationId(), action.confirmationId());
            }
            boolean replySaved = false;
            try {
                conversationService.saveMessage(action.conversationId(), Message.ROLE_ASSISTANT,
                        state.result(), action.model(), state.tokenUsage(),
                        snapshotContext(state.context()));
                replySaved = true;
            } catch (Exception e) {
                log.warn("[agent] confirm answer save failed, conversation={}, reason={}",
                        action.conversationId(), e.getMessage());
            }
            if (!replySaved) {
                log.warn("[agent] confirm answer not persisted, ledger stays as final source, "
                        + "confirmationTail={}", idTail(action.confirmationId()));
            }
            if (state.fallback()) {
                metrics.recordFallback();
            }
            log.info("[agent] confirm done, conversation={}, confirmationTail={}, fallback={}, " +
                            "costMs={}, " + "resultLength={}, toolCalls={}",
                    action.conversationId(),
                    idTail(action.confirmationId()), state.fallback(),
                    System.currentTimeMillis() - start, resultLength(state.result()),
                    state.taskTrace().size());
            ChatResponse response =
                    ChatResponse.of(action.conversationId(), state.result(), state.fallback(),
                            state.tokenUsage(), System.currentTimeMillis() - start, state.done(),
                            state.rootCause(), citations(state), confidence(state),
                            confidenceReason(state), toolTrace(state), state.taskClarify());
            response.setBusinessData(state.taskData());
            response.setBusiness(run != null ? businessResult(run) : businessResult(execution));
            return response;
        } catch (RuntimeException e) {
            // 超时/执行中不是业务失败：写步骤可能仍在后台推进，不能落“失败”助手消息。
            // 真实结果以 ai_tool_execution 台账为准，后续用同一确认标识重放。
            if (e instanceof BizException biz
                    && biz.getCode() == ErrorCode.CONFIRMATION_IN_PROGRESS.getCode()) {
                log.warn("[agent] confirm still running, keep token and wait for task ledger, "
                        + "conversation={}, confirmationTail={}", action.conversationId(),
                        idTail(action.confirmationId()));
                throw e;
            }
            // 保留令牌：业务失败可重试，重复执行由 ai_tool_execution 唯一键拦截
            boolean failureSaved = false;
            try {
                conversationService.saveMessage(action.conversationId(), Message.ROLE_ASSISTANT,
                        "确认操作执行失败，请稍后重新发起。", action.model(), null);
                failureSaved = true;
            } catch (Exception ignored) {
                // 落库失败不覆盖原始执行异常
            }
            throw e;
        }
    }

    /**
     * 把图内轨迹转成对外业务结果，供接口层区分业务成功、失败和执行中。
     *
     * ReAct 是只读诊断，不要求每一步工具都成功；固定读写则按“任一失败即失败”处理，
     * 因为固定写操作是原子业务动作，不能把部分失败当成整体成功。
     */
    private BusinessResult taskBusinessResult(WorkflowState state) {
        // ReAct 的每一步都是诊断尝试，最后一步可能是无数据或纠错失败；
        // 不能按最后一条轨迹判断整体失败，只要任一步拿到有效结果，诊断目标就已经完成。
        if (TaskOperation.parse(state.taskOperation()) == TaskOperation.REACT) {
            List<Map<String, Object>> trace = state.taskTrace();
            // 没有任何调用通常表示请求在解析阶段被追问或直接回答，不属于工具执行失败。
            if (trace.isEmpty()) {
                return BusinessResult.ok();
            }
            // 任一步成功即视为诊断完成；全部失败时才取第一条失败作为对外根因。
            for (Map<String, Object> item : trace) {
                if (Boolean.parseBoolean(String.valueOf(item.get("success")))) {
                    return BusinessResult.ok();
                }
            }
            Map<String, Object> first = trace.get(0);
            return BusinessResult.failure(
                    Texts.textOr(first.get("errorCode"), "TASK_FAILED"),
                    Texts.textOr(first.get("summary"), "任务执行失败"));
        }
        for (Map<String, Object> item : state.taskTrace()) {
            if (!Boolean.parseBoolean(String.valueOf(item.get("success")))) {
                return BusinessResult.failure(
                        Texts.textOr(item.get("errorCode"), "TASK_FAILED"),
                        Texts.textOr(item.get("summary"), "任务执行失败"));
            }
        }
        return BusinessResult.ok();
    }

    private BusinessResult businessResult(ToolExecution execution) {
        if (execution == null) {
            return BusinessResult.failure("NO_EXECUTION_RECORD", "未找到执行台账，写操作未落地");
        }
        if (execution.getStatus() == ToolExecution.Status.SUCCEEDED) {
            return BusinessResult.ok();
        }
        if (execution.getStatus() == ToolExecution.Status.RUNNING) {
            return BusinessResult.failure("IN_PROGRESS", "执行台账仍为 RUNNING");
        }
        if (execution.getStatus() == ToolExecution.Status.PENDING) {
            return BusinessResult.failure("NOT_EXECUTED", "确认尚未执行或已过期");
        }
        if (execution.getStatus() == ToolExecution.Status.CANCELLED) {
            return BusinessResult.failure("CANCELLED", "确认已取消");
        }
        return BusinessResult.failure(
                blankToDefault(execution.getErrorCode(), "EXECUTION_FAILED"),
                blankToDefault(execution.getErrorMessage(), "写操作执行失败"));
    }

    /**
     * 失败原因：没有台账、执行中、执行失败分别给出可检索的错误码
     */
    private String failureReason(ToolExecution execution) {
        if (execution == null) {
            return "NO_EXECUTION_RECORD:未找到执行台账，写操作未落地";
        }
        if (execution.getStatus() == ToolExecution.Status.RUNNING) {
            return "IN_PROGRESS:执行台账仍为 RUNNING";
        }
        if (execution.getStatus() == ToolExecution.Status.PENDING) {
            return "NOT_EXECUTED:确认尚未执行或已过期";
        }
        if (execution.getStatus() == ToolExecution.Status.CANCELLED) {
            return "CANCELLED:确认已取消";
        }
        return blankToDefault(execution.getErrorCode(), "EXECUTION_FAILED") + ":" +
                blankToDefault(execution.getErrorMessage(), "写操作执行失败");
    }

    /**
     * 多步运行转对外业务结果。
     *
     * PARTIAL_FAILED 必须如实告诉用户“前面步骤已生效、从第 N 步失败”，
     * 不能因为图正常返回就当成整体成功。
     */
    private BusinessResult businessResult(TaskRun run) {
        if (run == null) {
            return BusinessResult.failure("NO_EXECUTION_RECORD", "未找到运行台账，写操作未落地");
        }
        if (run.getStatus() == TaskRun.Status.SUCCEEDED) {
            return BusinessResult.ok();
        }
        if (run.getStatus() == TaskRun.Status.RUNNING) {
            return BusinessResult.failure("IN_PROGRESS", "执行台账仍为 RUNNING");
        }
        if (run.getStatus() == TaskRun.Status.PENDING) {
            return BusinessResult.failure("NOT_EXECUTED", "确认尚未执行或已过期");
        }
        if (run.getStatus() == TaskRun.Status.CANCELLED) {
            return BusinessResult.failure("CANCELLED", "确认已取消");
        }
        if (run.getStatus() == TaskRun.Status.PARTIAL_FAILED) {
            return BusinessResult.failure(
                    blankToDefault(run.getErrorCode(), "PARTIAL_FAILED"),
                    "已完成 " + Math.max(0, run.getCurrentStep() - 1) + " 步，第 "
                            + run.getCurrentStep() + " 步失败："
                            + blankToDefault(run.getErrorMessage(), "写操作执行失败"));
        }
        return BusinessResult.failure(
                blankToDefault(run.getErrorCode(), "EXECUTION_FAILED"),
                blankToDefault(run.getErrorMessage(), "写操作执行失败"));
    }

    /** 运行失败原因：带步骤序号，便于用户和运维定位。 */
    private String failureReason(TaskRun run) {
        if (run == null) {
            return "NO_EXECUTION_RECORD:未找到运行台账，写操作未落地";
        }
        if (run.getStatus() == TaskRun.Status.PARTIAL_FAILED) {
            return blankToDefault(run.getErrorCode(), "PARTIAL_FAILED")
                    + ":第 " + run.getCurrentStep() + " 步失败，"
                    + blankToDefault(run.getErrorMessage(), "写操作执行失败");
        }
        return blankToDefault(run.getErrorCode(), "EXECUTION_FAILED") + ":"
                + blankToDefault(run.getErrorMessage(), "写操作执行失败");
    }

    /**
     * 令牌已不存在时的幂等重放：已成功返回上次结果，执行中 409，失败要求重新发起
     */
    private ChatResponse replayFinished(String conversationId, String confirmationId, String userId,
                                        long start) {
        // 多步运行优先：run 是令牌删除后的执行事实源
        TaskRun run = taskRunService.find(userId, confirmationId).orElse(null);
        if (run != null) {
            if (!conversationId.equals(run.getConversationId())) {
                throw new BizException(ErrorCode.NOT_FOUND,
                        "确认请求不存在、已过期或已使用，请重新发起操作");
            }
            if (run.getStatus() == TaskRun.Status.SUCCEEDED) {
                log.info("[agent] confirm replay run, conversation={}, confirmationTail={}",
                        run.getConversationId(), idTail(confirmationId));
                ChatResponse response = ChatResponse.of(run.getConversationId(), run.getResult(),
                        false, null, System.currentTimeMillis() - start, true, "");
                response.setBusiness(BusinessResult.ok());
                return response;
            }
            if (run.getStatus() == TaskRun.Status.RUNNING) {
                throw new BizException(ErrorCode.CONFIRMATION_IN_PROGRESS);
            }
            if (run.getStatus() == TaskRun.Status.PENDING) {
                throw new BizException(ErrorCode.NOT_FOUND,
                        "确认请求已过期且未执行，请重新发起操作");
            }
            if (run.getStatus() == TaskRun.Status.CANCELLED) {
                throw new BizException(ErrorCode.NOT_FOUND,
                        "确认请求已取消或已过期，请重新发起操作");
            }
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED,
                    "上次确认执行失败：" + failureReason(run) + "，请重新发起操作");
        }

        // 兼容升级前的单步台账
        ToolExecution execution = legacyExecution(userId, confirmationId);
        if (execution == null || !conversationId.equals(execution.getConversationId())) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "确认请求不存在、已过期或已使用，请重新发起操作");
        }
        if (execution.getStatus() == ToolExecution.Status.SUCCEEDED) {
            log.info("[agent] confirm replay, conversation={}, confirmationTail={}",
                    execution.getConversationId(), idTail(confirmationId));
            ChatResponse response =
                    ChatResponse.of(execution.getConversationId(), execution.getResult(), false,
                            null, System.currentTimeMillis() - start, true, "");
            response.setBusiness(BusinessResult.ok());
            return response;
        }
        if (execution.getStatus() == ToolExecution.Status.RUNNING) {
            throw new BizException(ErrorCode.CONFIRMATION_IN_PROGRESS);
        }
        if (execution.getStatus() == ToolExecution.Status.PENDING) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "确认请求已过期且未执行，请重新发起操作");
        }
        if (execution.getStatus() == ToolExecution.Status.CANCELLED) {
            throw new BizException(ErrorCode.NOT_FOUND,
                    "确认请求已取消或已过期，请重新发起操作");
        }
        throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED,
                "上次确认执行失败：" + failureReason(execution) + "，请重新发起操作");
    }

    /** 台账错误字段可能来自历史数据或异常分支，对外必须给出稳定可读的兜底。 */
    private String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    /**
     * 兼容升级前的单步令牌：旧记录 executionId = confirmationId，
     * 新逻辑步骤 ID = confirmationId#1；两处都查，避免升级窗口内误判“未执行”。
     */
    private ToolExecution legacyExecution(String userId, String confirmationId) {
        ToolExecution execution = toolExecutionService.find(userId, confirmationId).orElse(null);
        if (execution != null) {
            return execution;
        }
        return toolExecutionService.find(userId,
                TaskRunService.stepExecutionId(confirmationId, 1)).orElse(null);
    }

    /**
     * 确认执行入口：直接执行已确认的单个写工具，不重新意图识别、读写分类或参数抽取。
     *
     * 执行事实以 ai_tool_execution 为准；TaskConfirmService 只顺序调用
     * 写节点、回答节点、收尾节点，保留整体超时边界。
     */
    private WorkflowState invokeTaskConfirm(ConfirmationService.PendingAction action,
                                            String userId, String query, long start) {
        return executeConfirmWithTimeout(
                () -> taskConfirmService.execute(action, userId, query),
                action.conversationId(), start);
    }

    /**
     * 断点恢复：从最近 checkpoint 继续执行（无可恢复任务时返回 empty）。
     *
     * 机制说明：LangGraph 的 checkpoint 是按“超级步骤”在节点执行边界落盘的
     * （每执行完一个节点保存一次完整状态）。invoke(GraphInput.resume()) 会加载
     * 该 threadId 最近一次 checkpoint，从上次中断的节点之后继续跑，而不是重头执行。
     *
     * 与 chat 一样在会话锁内执行，防止恢复与新请求并发写 checkpoint 和消息。
     * 仅当最后一轮未闭环（最新消息是 user，例如进程中断）时才恢复；超时降级轮已经
     * 落库 assistant，属于已闭环，不会二次恢复产生双份回答。恢复出的结果同样在锁内
     * 写回 assistant，保证短期记忆闭环。
     */
    public Optional<WorkflowState> resume(String conversationId) {
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        return sessionLock.executeWithLock(conversationId, () -> {
            // 最后一轮已闭环（含超时降级轮）：无需恢复，避免重复产生 assistant
            if (conversationService.isLastTurnClosed(conversationId)) {
                log.info("[agent] resume skipped, last turn already closed, thread={}",
                        conversationId);
                return Optional.empty();
            }
            try {
                Optional<WorkflowState> state = agentGraph.invoke(GraphInput.resume(), config);
                // 恢复出的结果同样落库 assistant，保证续聊上下文不丢这一轮
                state.ifPresent(s -> {
                    try {
                        conversationService.saveMessage(conversationId, Message.ROLE_ASSISTANT,
                                s.result(), s.model(), s.tokenUsage(),
                                snapshotContext(s.context()));
                    } catch (Exception e) {
                        log.warn("[agent] resume save assistant message failed, degrade: {}",
                                e.getMessage());
                    }
                    // 恢复轮转人工同样自动建单（运营闭环；失败降级不阻断）
                    if (s.handoff()) {
                        ticketService.createIfAbsent(UserContext.require(), conversationId,
                                s.query(), s.rootCause());
                    }
                });
                return state;
            } catch (Exception e) {
                log.error("[agent] resume failed, thread={}", conversationId, e);
                throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED,
                        "断点恢复失败: " + Throwables.rootMessage(e));
            }
        });
    }

    /**
     * 查询 checkpoint 快照（不存在或 saver 故障返回 empty）。
     *
     * StateSnapshot 是 LangGraph 对“某线程当前执行位置”的只读视图：
     * 包含最新状态数据（state()）、下一个待执行节点（next()）等元信息，
     * 可用于排查“图跑到哪一步了”。
     */
    public Optional<StateSnapshot<WorkflowState>> getState(String threadId) {
        try {
            return Optional.ofNullable(
                    agentGraph.getState(RunnableConfig.builder().threadId(threadId).build()));
        } catch (Exception e) {
            log.warn("[agent] getState failed, thread={}: {}", threadId, e.getMessage());
            return Optional.empty();
        }
    }

    // ---- 内部实现 ----

    /**
     * 检索上下文快照截断：写入 assistant 消息 context 列，仅用于问题排查与取证。
     * 改写视图读取真实 assistant 回答，不读取该快照（快照不是对话内容，会把追问带偏）。
     * 空上下文（检索失败/超时降级轮）返回 null。
     */
    private String snapshotContext(String context) {
        if (context == null || context.isBlank()) {
            return null;
        }
        int maxLength = properties.getLimits().getContextSnapshotMax();
        return context.length() > maxLength ? context.substring(0, maxLength) : context;
    }

    /**
     * 任务证据转响应引用：结构来源于 ToolEvidence，字段缺失时安全降级为空串
     */
    private List<ChatResponse.Citation> citations(WorkflowState state) {
        return state.taskEvidence().stream()
                .map(item -> new ChatResponse.Citation(Texts.textOr(item.get("type"), "TOOL"),
                        Texts.textOr(item.get("sourceId"), ""), Texts.textOr(item.get("title"), ""),
                        Texts.textOr(item.get("snippet"), ""), doubleOf(item.get("score")))).toList();
    }

    /**
     * 工具轨迹转响应结构；args 保持 Map 形态透出，便于前端展示模型实际调用参数
     */
    private List<ChatResponse.ToolCallTrace> toolTrace(WorkflowState state) {
        return state.taskTrace().stream()
                .map(item -> new ChatResponse.ToolCallTrace(Texts.textOr(item.get("tool"), ""),
                        mapOf(item.get("args")), Texts.textOr(item.get("purpose"), ""),
                        Boolean.TRUE.equals(item.get("success")), Texts.textOr(item.get("summary"), ""),
                        Texts.textOr(item.get("errorCode"), ""), longOf(item.get("costMs")),
                        (int) longOf(item.get("stepNo")))).toList();
    }

    /**
     * 待确认动作转响应卡片；无待确认动作返回 null
     */
    private ChatResponse.PendingAction pendingAction(WorkflowState state) {
        Map<String, Object> pending = state.taskPendingAction();
        if (pending.isEmpty()) {
            return null;
        }
        String confirmationId = Texts.textOr(pending.get("confirmationId"), "");
        if (confirmationId.isBlank()) {
            return null;
        }
        return new ChatResponse.PendingAction(confirmationId, Texts.textOr(pending.get("tool"), ""),
                mapOf(pending.get("args")), Texts.textOr(pending.get("summary"), ""),
                Texts.textOr(pending.get("risk"), ""), longOf(pending.get("expiresAt")),
                stepsOf(pending.get("steps")));
    }

    /**
     * 待确认步骤列表转响应结构；缺失/类型不符返回空列表，兼容旧 checkpoint。
     */
    private List<ChatResponse.PendingAction.Step> stepsOf(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<ChatResponse.PendingAction.Step> steps = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> row = mapOf(map);
                steps.add(new ChatResponse.PendingAction.Step(
                        (int) longOf(row.get("stepNo")),
                        Texts.textOr(row.get("tool"), ""),
                        mapOf(row.get("args")),
                        Texts.textOr(row.get("summary"), "")));
            }
        }
        return steps;
    }

    /**
     * 同步与流式共用的响应组装：答案、引用、置信度、工具轨迹、待确认卡片和业务结果。
     *
     * 待确认动作存在时业务结果固定为 NOT_EXECUTED（等待人工确认），不能被“图正常返回”
     * 误判成业务成功；普通任务轮才按工具痕迹计算业务成败。
     *
     * @param conversationId 会话 ID
     * @param state          图最终状态
     * @param start          本轮开始时间（用于 costMs）
     * @return 对外统一响应对象
     */
    private ChatResponse buildChatResponse(String conversationId, WorkflowState state, long start) {
        ChatResponse response = ChatResponse.of(conversationId, state.result(), state.fallback(),
                state.tokenUsage(), System.currentTimeMillis() - start, state.done(),
                state.rootCause(), citations(state), confidence(state), confidenceReason(state),
                toolTrace(state), state.taskClarify());
        response.setBusinessData(state.taskData());
        response.setPendingAction(pendingAction(state));
        if (response.getPendingAction() != null) {
            response.setBusiness(BusinessResult.failure("NOT_EXECUTED", "等待用户确认写操作"));
        } else if (QaIntent.TASK.equals(state.intent())) {
            response.setBusiness(taskBusinessResult(state));
        }
        return response;
    }

    /**
     * 把数值对象转成 double；非数值返回 0.0。
     */
    private double doubleOf(Object value) {
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }

    /**
     * 把数值对象转成 long；非数值返回 0。
     */
    private long longOf(Object value) {
        return value instanceof Number number ? number.longValue() : 0L;
    }

    /**
     * 任务链路返回工具置信度；知识问答返回检索证据与质检合并后的置信度。
     */
    private Double confidence(WorkflowState state) {
        if (QaIntent.TASK.equals(state.intent())) {
            return state.taskConfidence();
        }
        if (QaIntent.KNOWLEDGE_QA.equals(state.intent()) && state.finalConfidence() > 0.0) {
            return state.finalConfidence();
        }
        return null;
    }

    /**
     * 置信度说明与 confidence 同步：知识问答返回检索证据指标，其他轮为空串。
     */
    private String confidenceReason(WorkflowState state) {
        if (QaIntent.TASK.equals(state.intent())) {
            return state.taskConfidenceReason();
        }
        if (QaIntent.KNOWLEDGE_QA.equals(state.intent())) {
            return state.retrievalEvidence().reason();
        }
        return "";
    }

    /** 把对象安全转成字符串键 Map；类型不符返回空 Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mapOf(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    /**
     * 入口输入规整：剔除控制字符 + 去首尾空白，清洗后为空抛 400。
     *
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
     * 锁内准备并执行图（同步路径）：保存用户消息 → 双视图装配 → 改写/话题归属 → 图执行。
     *
     * 调用约束：必须已在 {@code sessionLock.executeWithLock(...)} 内调用。
     * 会话锁覆盖“用户消息落库 → 图执行 → 助手消息落库”整轮闭环，保证同一会话
     * 不会并发进入，也不会出现上一轮回答尚未落库、下一轮就读历史的乱序问题。
     *
     * 职责边界：不处理限流、输入清洗、模型解析和会话创建，这些已经在 chat() 入口完成；
     * 不负责助手消息落库、指标上报和转人工建单，由调用方拿到结果后处理。图执行异常
     * 转成业务错误码抛出。
     *
     * @param conversationId 会话 ID（同时作为 LangGraph threadId 与 checkpoint 隔离维度）
     * @param userId         当前用户 ID（入图后按用户隔离知识库与工具数据）
     * @param query          清洗后的原始用户输入（用于落库与改写）
     * @param model          本轮模型名（已由 ModelRegistry 解析）
     * @param answerMode     回答模式（已归一，白名单外回默认）
     * @param visibility     检索可见范围（已归一，白名单外回默认）
     * @return 图执行最终状态；超时路径返回 {@code fallback=true} 的降级状态
     */
    private WorkflowState prepareAndInvoke(String conversationId, String userId, String query,
                                           String model, String answerMode, String visibility) {
        // 步骤1：锁内准备本轮上下文：落用户消息、装配改写/生成双视图、改写与话题段归属
        PreparedTurn turn = prepareTurn(conversationId, userId, query, model);
        // 步骤2：把原始输入、改写句、生成视图交给完整图执行（意图 → 检索/工具 → 生成 → 校验 → 收尾）
        return invokeGraph(conversationId, userId, query, turn.rewrite().rewritten(),
                turn.history(), turn.summary(), turn.memory(), model, answerMode, visibility);
    }

    /**
     * 每轮锁内准备（同步/流式共用），装配两条独立的历史视图：
     *
     * 改写视图取跨话题最近 N 条，只给改写器，用于指代消解和话题判断；生成视图取
     * 当前话题段内原文，只给生成 LLM，其他话题不带，避免把无关上下文混入回答。
     *
     * @param conversationId 会话 ID（threadId）
     * @param userId         当前用户 ID（用于召回长期记忆）
     * @param query          原始用户输入（落库与改写输入）
     * @param model          本轮模型名（改写调用与消息落库共用）
     * @return 每轮准备产物（改写结果 + 生成视图 + 当前段摘要 + 长期记忆文本）
     */
    private PreparedTurn prepareTurn(String conversationId, String userId, String query,
                                     String model) {
        // 步骤1：存用户消息（段号待步骤5.5回填），拿自增 id 供话题段起点锚定
        Message userMessage =
                conversationService.saveMessage(conversationId, Message.ROLE_USER, query, model,
                        null);
        // 步骤2：改写视图（跨话题最近 N 条，只给改写器，不进生成）
        List<Map<String, String>> rewriteView =
                conversationService.rewriteHistoryView(conversationId,
                        properties.getHistory().getRewriteWindowSize(),
                        properties.getHistory().getRewriteItemMaxLength());
        // 步骤3：已有话题段 → 话题标签列表（供改写器做归属判断）
        List<ConversationSummary> segments = segmentService.listSegments(conversationId);
        List<String> topics = segments.stream().map(ConversationSummary::getTopic)
                .filter(t -> t != null && !t.isBlank()).toList();
        // 步骤4：改写 + 话题归属（一次 LLM 调用；内部无历史短路/LLM 失败降级）
        RewriteResult rewrite = queryRewriter.rewrite(query, rewriteView, topics, model);
        // 步骤5：话题段归属：命中旧段复用（切回任务A只读A的窗口+摘要），新话题新建段
        ConversationSummary segment =
                segmentService.resolveSegment(conversationId, segments, rewrite,
                        userMessage.getId());
        // 步骤5.5：回填本轮 user 消息的段归属（assistant 落库时继承），生成视图按段号精确过滤防混段
        conversationService.updateMessageSegment(userMessage.getId(), segment.getSegmentId());
        // 步骤6：生成视图（当前话题段内原文，只给生成 LLM；token 预算装配，排除当前消息）
        List<Map<String, String>> generationView =
                conversationService.generationHistoryView(conversationId, segment.getSegmentId(),
                        properties.getHistory().getGenerationMaxTokens(),
                        properties.getHistory().getGenerationItemMaxLength(),
                        properties.getLimits().getGenerationCandidateLimit());
        // 步骤7：异步触发段摘要（锁内仅提交任务，不等待 LLM 执行）
        summaryService.triggerIfNeeded(conversationId, segment);
        String summary = segment.getContent() == null ? "" : segment.getContent();
        // 步骤8：召回用户级长期记忆（跨会话稳定偏好/事实；失败降级为空，不影响本轮）
        String memory = userMemoryService.recallForPrompt(userId, query);
        log.info("[agent] turn prepared, conversation={}, segment={}, rewritten={}, history={}, " +
                        "summaryLength={}, memoryLength={}", conversationId, segment.getSegmentId(),
                rewrite.rewritten() != null && !rewrite.rewritten().isBlank(),
                generationView.size(), summary.length(), memory.length());
        return new PreparedTurn(rewrite, generationView, summary, memory);
    }

    /**
     * 组装图输入：query / 模型名 / 改写句 / 生成视图 / 段摘要（空值兜底为空串或空列表）。
     *
     * @param query     原始用户输入
     * @param rewritten 改写后的自包含句
     * @param history   生成视图（当前话题段内原文，role/content 键值对）
     * @param summary   当前话题段摘要（可为空）
     * @param memory    用户长期记忆文本（可为空）
     * @param model     本轮模型名
     * @return 图输入键值对（key 为 WorkflowState 字段名常量）
     */
    private Map<String, Object> buildInputs(String query, String rewritten,
                                            List<Map<String, String>> history, String summary,
                                            String memory, String model, String userId,
                                            String conversationId, String answerMode,
                                            String visibility) {
        return Map.of(WorkflowState.QUERY, query, WorkflowState.MODEL, model,
                WorkflowState.REWRITTEN_QUERY, rewritten == null ? "" : rewritten,
                WorkflowState.HISTORY, history == null ? List.of() : history, WorkflowState.SUMMARY,
                summary == null ? "" : summary, WorkflowState.MEMORY,
                memory == null ? "" : memory, WorkflowState.USER_ID, userId,
                WorkflowState.CONVERSATION_ID, conversationId, WorkflowState.ANSWER_MODE,
                answerMode, WorkflowState.VISIBILITY, visibility);
    }

    /**
     * 图执行（同步路径）：提交专用线程池 + 整体超时中止 + 异常转换为业务错误码。
     *
     * 超时策略：Future 超时后调用 cancel(true) 尽力中断工作线程，并直接返回降级应答，
     * 不再等待或接收可能稍后完成的返回值；user 和 assistant 仍照常落库，保证一问一答闭环。
     * 取消是否成功只用于观测，不能说明工作线程一定已经停止。
     *
     * @param conversationId 会话 ID（threadId，checkpoint 隔离维度）
     * @param userId         当前用户 ID（入图注入）
     * @param query          原始用户输入（入图与日志使用）
     * @param rewritten      改写后的自包含句（图内实际使用）
     * @param history        生成视图（当前话题段内原文，role/content 键值对）
     * @param summary        当前话题段摘要（可为空）
     * @param memory         用户长期记忆文本（可为空）
     * @param model          本轮模型名（已解析）
     * @param answerMode     回答模式（已归一）
     * @param visibility     检索可见范围（已归一）
     * @return 图执行最终状态；超时返回降级 WorkflowState（fallback=true）
     */
    private WorkflowState invokeGraph(String conversationId, String userId, String query,
                                      String rewritten, List<Map<String, String>> history,
                                      String summary, String memory, String model,
                                      String answerMode, String visibility) {
        long start = System.currentTimeMillis();
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        Map<String, Object> inputs =
                buildInputs(query, rewritten, history, summary, memory, model, userId,
                        conversationId, answerMode, visibility);
        return executeGraphWithTimeout(agentGraph, inputs, config, conversationId, start);
    }

    /**
     * 统一的图执行入口：提交专用线程池 + 整体超时 + 超时取消。
     *
     * 只执行一次，不做 catch 后重跑：写工具、模型调用都可能产生副作用，
     * 盲目重跑会重复执行工具或重复计费。超时后统一按 TIMEOUT 处理，不会在等待返回后
     * 再接收结果；写操作是否已提交以 ai_tool_execution 台账为准。
     */
    private WorkflowState executeGraphWithTimeout(CompiledGraph<WorkflowState> graph,
                                                  Map<String, Object> inputs, RunnableConfig config,
                                                  String conversationId, long start) {
        long timeoutSeconds = Math.max(10, properties.getExecutionTimeoutSeconds());
        Future<WorkflowState> future = submitGraph(graph, inputs, config, conversationId);
        try {
            return awaitGraph(future, timeoutSeconds, conversationId, start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行被中断");
        }
    }

    /**
     * 流式图执行：在线程池内驱动 LangGraph4j 的 AsyncGenerator，每完成一个节点推送 node 事件。
     *
     * 复用同步链路的有界执行语义：整体超时、超时取消、队列满 429、失败归一。
     * 与同步链路的差别只有两点：
     * 1. 每轮节点输出都会转成精简视图推给前端；
     * 2. sink 返回 false（客户端断开）时提前停止迭代，返回 null 且不落助手消息。
     *
     * @param inputs         图输入
     * @param config         线程配置（threadId = conversationId，checkpoint 隔离维度）
     * @param conversationId 会话 ID（日志与拒绝处理）
     * @param start          本轮开始时间（耗时指标）
     * @param sink           SSE 事件接收端
     * @return 最终图状态；客户端中途断开返回 null；超时返回 fallback 降级状态
     */
    private WorkflowState executeGraphWithStream(Map<String, Object> inputs, RunnableConfig config,
                                                 String conversationId, long start,
                                                 AgentStreamSink sink) {
        long timeoutSeconds = Math.max(10, properties.getExecutionTimeoutSeconds());
        Future<StreamOutcome> future;
        try {
            future = agentExecutor.submit(() -> {
                WorkflowState last = null;
                for (NodeOutput<WorkflowState> output : agentGraph.stream(inputs, config)) {
                    WorkflowState current = output.state();
                    if (current != null) {
                        last = current;
                    }
                    // START/END 不是业务节点，不推送；其余节点完成一个推一个
                    if (!output.isSTART() && !output.isEND()
                            && !sink.emit(AgentStreamEvent.of("node",
                            nodeView(output, current)))) {
                        return new StreamOutcome(last, true);
                    }
                }
                return new StreamOutcome(last, false);
            });
        } catch (RejectedExecutionException e) {
            log.warn("[agent] executor overloaded, reject stream request, thread={}",
                    conversationId);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }

        BoundedCall.Outcome<StreamOutcome> outcome =
                BoundedCall.await(future, timeoutSeconds * 1000L);
        if (outcome.status() == BoundedCall.Status.SUCCESS) {
            StreamOutcome value = outcome.value();
            if (value == null) {
                throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行未返回结果");
            }
            metrics.recordGraphDuration(System.currentTimeMillis() - start);
            if (value.cancelled()) {
                return null;
            }
            return value.state() == null ? timeoutFallback() : value.state();
        }
        if (outcome.status() == BoundedCall.Status.INTERRUPTED) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "流式图执行被中断");
        }
        if (outcome.status() == BoundedCall.Status.FAILED) {
            throw graphFailure(outcome.error(), conversationId);
        }
        if (outcome.status() == BoundedCall.Status.REJECTED) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }
        long costMs = System.currentTimeMillis() - start;
        metrics.recordGraphDuration(costMs);
        log.warn("[agent] stream timeout after {}s, thread={}, task cancelled, return fallback",
                timeoutSeconds, conversationId);
        return timeoutFallback();
    }

    /**
     * 节点进度视图：只透出前端需要的最小状态，避免把整份上下文和检索证据打到流里。
     *
     * @param output 图节点输出（节点名 + 该节点执行后的完整状态）
     * @param state  节点后的状态；可能为 null（防御空输出）
     * @return 可序列化的精简节点视图
     */
    private Map<String, Object> nodeView(NodeOutput<WorkflowState> output, WorkflowState state) {
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("node", output.node());
        if (state == null) {
            return view;
        }
        view.put("intent", state.intent());
        view.put("taskOperation", state.taskOperation());
        view.put("retryCount", state.retryCount());
        view.put("reactStep", state.reactStep());
        view.put("fallback", state.fallback());
        view.put("handoff", state.handoff());
        view.put("done", state.done());
        String error = state.error();
        if (error != null && !error.isBlank()) {
            view.put("error", Texts.truncate(error,
                    properties.getLimits().getMaxValidationReasonLength()));
        }
        return view;
    }

    /**
     * 确认执行有界执行：写链路同样必须有整体超时。
     *
     * 超时不能伪装成业务失败：写步骤可能已经提交，台账会保留 RUNNING。
     * 调用方收到“执行中”后，应在台账超过 staleSeconds 后用同一 confirmationId 重试，由幂等闸门决定返回上次成功结果还是接管执行。
     */
    private WorkflowState executeConfirmWithTimeout(Supplier<WorkflowState> task,
                                                    String conversationId,
                                                    long start) {
        long timeoutSeconds = Math.max(10, properties.getConfirmTimeoutSeconds());
        Future<WorkflowState> future = submitTask(task, conversationId);
        BoundedCall.Outcome<WorkflowState> outcome =
                BoundedCall.await(future, timeoutSeconds * 1000L);
        if (outcome.status() == BoundedCall.Status.SUCCESS) {
            return completeGraph(outcome.value(), conversationId, start);
        }
        if (outcome.status() == BoundedCall.Status.INTERRUPTED) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "确认执行被中断");
        }
        if (outcome.status() == BoundedCall.Status.FAILED) {
            if (outcome.error() instanceof BizException biz
                    && biz.getCode() == ErrorCode.CONFIRMATION_IN_PROGRESS.getCode()) {
                throw biz;
            }
            throw graphFailure(outcome.error(), conversationId);
        }
        if (outcome.status() == BoundedCall.Status.REJECTED) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }
        long costMs = System.currentTimeMillis() - start;
        metrics.recordGraphDuration(costMs);
        log.warn("[agent] confirm timeout after {}s, thread={}, keep token and task ledger",
                timeoutSeconds, conversationId);
        throw new BizException(ErrorCode.CONFIRMATION_IN_PROGRESS,
                "确认执行超时，操作可能仍在后台执行，请稍后用同一确认标识重试");
    }

    /**
     * 提交到专用线程池；队列满直接 429，避免请求在队列里等到超时。
     */
    private Future<WorkflowState> submitGraph(CompiledGraph<WorkflowState> graph,
                                              Map<String, Object> inputs, RunnableConfig config,
                                              String conversationId) {
        return submitTask(() -> graph.invoke(inputs, config).orElse(null), conversationId);
    }

    /** 统一提交任务到专用线程池；图执行与确认执行共用同一拒绝策略。 */
    private Future<WorkflowState> submitTask(Supplier<WorkflowState> task,
                                             String conversationId) {
        try {
            return agentExecutor.submit(task::get);
        } catch (RejectedExecutionException e) {
            log.warn("[agent] executor overloaded, reject request, thread={}", conversationId);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }
    }

    /**
     * 等待结果；超时、取消、异常归一由 {@link BoundedCall} 统一处理。
     *
     * 翻译成图执行语义：成功完成、超时降级、中断、失败。
     */
    private WorkflowState awaitGraph(Future<WorkflowState> future, long timeoutSeconds,
                                     String conversationId,
                                     long start) throws InterruptedException {
        BoundedCall.Outcome<WorkflowState> outcome =
                BoundedCall.await(future, timeoutSeconds * 1000L);
        if (outcome.status() == BoundedCall.Status.SUCCESS) {
            return completeGraph(outcome.value(), conversationId, start);
        }
        if (outcome.status() == BoundedCall.Status.INTERRUPTED) {
            throw new InterruptedException("图执行被中断");
        }
        if (outcome.status() == BoundedCall.Status.FAILED) {
            throw graphFailure(outcome.error(), conversationId);
        }
        if (outcome.status() == BoundedCall.Status.REJECTED) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }

        // TIMEOUT：已请求取消，但不保证工作线程立即停止；返回降级应答保证一问一答闭环。
        long costMs = System.currentTimeMillis() - start;
        metrics.recordGraphDuration(costMs);
        log.warn("[agent] execution timeout after {}s, thread={}, task cancelled, return fallback answer",
                timeoutSeconds, conversationId);
        return timeoutFallback();
    }

    /**
     * 成功完成：统一空结果检查和耗时指标。
     */
    private WorkflowState completeGraph(WorkflowState state, String conversationId, long start) {
        long costMs = System.currentTimeMillis() - start;
        if (state == null) {
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行未返回结果");
        }
        metrics.recordGraphDuration(costMs);
        return state;
    }

    /**
     * 失败统一转换：BizException 原样上抛，其他异常记录根因后转 AGENT_EXECUTION_FAILED。
     */
    private BizException graphFailure(Throwable cause, String conversationId) {
        if (cause instanceof BizException biz) {
            return biz;
        }
        log.error("[agent] graph execution failed, thread={}", conversationId, cause);
        return new BizException(ErrorCode.AGENT_EXECUTION_FAILED,
                "图执行失败: " + Throwables.rootMessage(cause));
    }

    /**
     * 超时降级状态：本轮 user + assistant 都落库，历史一问一答闭环，用户可直接重发。
     */
    private WorkflowState timeoutFallback() {
        return new WorkflowState(Map.of(WorkflowState.RESULT,
                promptTemplateService.resolve("FALLBACK_TIMEOUT_ANSWER",
                        PromptTemplates.FALLBACK_TIMEOUT_ANSWER), WorkflowState.FALLBACK, true,
                WorkflowState.DONE, true));
    }

    /**
     * 返回结果文本长度；null 按 0 处理，供日志统一记录。
     */
    private int resultLength(String result) {
        return result == null ? 0 : result.length();
    }

    /**
     * 确认令牌脱敏：长 ID 仅保留末 8 位，短 ID 完全隐藏。
     */
    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }

    /**
     * 回答模式归一：白名单 auto/deep，非法值（含 null）回默认 auto。
     * 入图前收敛，图内节点直接消费不再校验。
     */
    private String normalizeAnswerMode(String answerMode) {
        return ChatOptions.normalizeAnswerMode(answerMode,
                properties.getRequest().getDefaultAnswerMode());
    }

    /**
     * 检索可见范围归一：白名单 all/private，非法值（含 null）回默认 all。
     * 入图前收敛，图内节点直接消费不再校验。
     */
    private String normalizeVisibility(String visibility) {
        return ChatOptions.normalizeVisibility(visibility,
                properties.getRequest().getDefaultVisibility());
    }

    /**
     * 每轮准备产物：改写结果 + 生成视图 + 当前段摘要，供同步/流式共用
     */
    private record PreparedTurn(RewriteResult rewrite, List<Map<String, String>> history,
                                String summary, String memory) {
    }

    /**
     * 流式图执行结果。
     *
     * @param state     最后一个节点后的图状态；客户端提前断开时可能只是半成品
     * @param cancelled true=客户端断开导致主动停止迭代，本轮不应落助手消息
     */
    private record StreamOutcome(WorkflowState state, boolean cancelled) {
    }
}
