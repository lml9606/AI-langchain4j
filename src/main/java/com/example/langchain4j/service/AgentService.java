package com.example.langchain4j.service;

import com.example.langchain4j.audit.AuditEntry;
import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.concurrent.BoundedCall;
import com.example.langchain4j.common.constant.ChatOptions;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.common.util.Throwables;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.ChatRequest;
import com.example.langchain4j.dto.request.ConfirmRequest;
import com.example.langchain4j.dto.response.BusinessResult;
import com.example.langchain4j.dto.response.ChatResponse;
import com.example.langchain4j.dto.response.TaskRunDto;
import com.example.langchain4j.graph.qa.QaIntent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.guard.SessionLock;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.repository.entity.ConversationSummary;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.entity.TaskRun;
import com.example.langchain4j.repository.entity.TaskStep;
import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.service.model.RewriteResult;
import com.example.langchain4j.tool.runtime.ConfirmationService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.state.StateSnapshot;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;

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

    private final CompiledGraph<WorkflowState> agentGraph;
    private final CompiledGraph<WorkflowState> taskConfirmGraph;
    private final RateLimiter rateLimiter;
    private final SessionLock sessionLock;
    private final ConversationService conversationService;
    private final QueryRewriter queryRewriter;
    private final SegmentService segmentService;
    private final AuditService auditService;
    private final SummaryService summaryService;
    private final ModelRegistry modelRegistry;
    private final TicketService ticketService;
    private final ThreadPoolTaskExecutor agentExecutor;
    private final AgentProperties properties;
    private final AgentMetrics metrics;
    private final PromptTemplateService promptTemplateService;
    private final ConfirmationService confirmationService;
    private final ToolExecutionService toolExecutionService;
    private final TaskRunService taskRunService;

    /**
     * 注入图执行、会话、限流、审计、模型与确认等编排依赖。
     */
    public AgentService(@Qualifier("agentGraph") CompiledGraph<WorkflowState> agentGraph,
                        @Qualifier("taskConfirmGraph") CompiledGraph<WorkflowState> taskConfirmGraph,
                        RateLimiter rateLimiter, SessionLock sessionLock,
                        ConversationService conversationService, QueryRewriter queryRewriter,
                        SegmentService segmentService, AuditService auditService,
                        SummaryService summaryService, ModelRegistry modelRegistry,
                        TicketService ticketService, ThreadPoolTaskExecutor agentExecutor,
                        AgentProperties properties, AgentMetrics metrics,
                        PromptTemplateService promptTemplateService,
                        ConfirmationService confirmationService,
                        ToolExecutionService toolExecutionService,
                        TaskRunService taskRunService) {
        this.agentGraph = agentGraph;
        this.taskConfirmGraph = taskConfirmGraph;
        this.rateLimiter = rateLimiter;
        this.sessionLock = sessionLock;
        this.conversationService = conversationService;
        this.queryRewriter = queryRewriter;
        this.segmentService = segmentService;
        this.auditService = auditService;
        this.summaryService = summaryService;
        this.modelRegistry = modelRegistry;
        this.ticketService = ticketService;
        this.agentExecutor = agentExecutor;
        this.properties = properties;
        this.metrics = metrics;
        this.promptTemplateService = promptTemplateService;
        this.confirmationService = confirmationService;
        this.toolExecutionService = toolExecutionService;
        this.taskRunService = taskRunService;
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
            // 新用户消息视为放弃旧确认：先取消旧令牌/台账，再按新意图执行。
            confirmationService.cancelPending(conversationId, userId, "USER_NEW_MESSAGE");
            try {
                // 旧确认令牌删除后，任务表也必须同步取消；否则 ai_task_run 会一直停在
                // WAITING_CONFIRM，任务进度接口和恢复 worker 都会看到一条永远不结束的任务。
                taskRunService.cancelByConversation(userId, conversationId);
            } catch (RuntimeException e) {
                log.warn("[agent] cancel stale task runs failed, conversation={}, reason={}",
                        conversationId, e.getMessage());
            }
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
        log.info("[agent] chat done, conversation={}, intent={}, fallback={}, handoff={}, " +
                        "costMs={}, resultLength={}, tokenUsage={}, toolCalls={}, rootCause={}",
                conversationId, state.intent(), state.fallback(), state.handoff(),
                System.currentTimeMillis() - start, resultLength(state.result()),
                state.tokenUsage(), state.taskTrace().size(), state.rootCause());
        ChatResponse response = ChatResponse.of(conversationId, state.result(), state.fallback(),
                state.tokenUsage(), System.currentTimeMillis() - start, state.done(),
                state.rootCause(), citations(state), confidence(state), confidenceReason(state),
                toolTrace(state), state.taskClarify());
        response.setPendingAction(pendingAction(state));
        response.setTaskRunId(state.taskRunId());
        response.setTaskStatus(state.taskRunStatus());
        return response;
    }

    /**
     * 确认写操作：令牌只做短期授权，执行状态由数据库台账兜底。
     *
     * <p>完整生命周期：
     * <ol>
     *   <li>{@code preview} 只读校验令牌，不消耗；</li>
     *   <li>会话归属校验；</li>
     *   <li>严格会话锁内再次校验令牌：使用 Watchdog 自动续期，Redis 故障 fail-closed；</li>
     *   <li>执行确认图：写工具在同一数据库事务里写业务数据 + 执行台账；</li>
     *   <li>成功 {@code finish} 删除令牌；失败保留令牌供重试。</li>
     * </ol>
     *
     * <p>令牌不存在时不再直接 404：先查执行台账，已成功则按幂等重放返回上次结果，
     * 正在执行则返回 409，保证"响应丢了再点一次"不会重复写。
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

        // 严格会话锁内只读取一次令牌：归属校验通过后再进入锁，避免双重 preview。
        return sessionLock.executeWithLockStrict(conversationId, () -> {
            Optional<ConfirmationService.PendingAction> current =
                    confirmationService.preview(conversationId, request.getConfirmationId(),
                            userId);
            if (current.isEmpty()) {
                return replayFinished(conversationId, request.getConfirmationId(), userId, start);
            }
            ConfirmationService.PendingAction action = current.get();
            // 自动取消后 Redis 令牌可能尚未删除（例如删除失败/Redis 恢复后旧值仍在）：
            // 任务或台账已 CANCELLED 时不能再执行确认图，直接按幂等重放返回取消结果。
            TaskRun currentRun = action.runId().isBlank() ? null
                    : taskRunService.find(userId, action.runId()).orElse(null);
            ToolExecution currentExecution = action.runId().isBlank()
                    ? toolExecutionService.find(userId, action.confirmationId()).orElse(null) : null;
            if (currentRun != null && currentRun.getPlanHash() != null
                    && !currentRun.getPlanHash().isBlank()
                    && !currentRun.getPlanHash().equals(
                    taskRunService.planHash(JsonUtil.toJson(action.plan())))) {
                // 令牌里的计划与生成确认卡片时的计划指纹不一致：可能是 Redis 被篡改，
                // 也可能是一次性令牌被错误复用。直接失败，不执行任何写步骤。
                confirmationService.finish(action.conversationId(), action.confirmationId());
                log.warn("[agent] confirm plan hash mismatch, conversation={}, runTail={}",
                        conversationId, idTail(action.runId()));
                throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED,
                        "确认计划校验失败，请重新发起操作");
            }
            if (currentRun != null && currentRun.getStatus() == TaskRun.Status.RUNNING) {
                // 另一个实例可能正在执行写步骤（例如恢复 worker），此时取消或重复确认
                // 都不能声称“没有修改数据”；统一返回执行中，让客户端查询任务状态。
                throw new BizException(ErrorCode.CONFIRMATION_IN_PROGRESS);
            }
            if (currentRun != null && currentRun.getStatus() == TaskRun.Status.SUCCEEDED) {
                // 执行已完成但令牌尚未删除（极小窗口）：按幂等重放返回结果，不重复执行。
                confirmationService.finish(action.conversationId(), action.confirmationId());
                return replayFinished(conversationId, request.getConfirmationId(), userId, start);
            }
            if ((currentRun != null && currentRun.getStatus() == TaskRun.Status.CANCELLED)
                    || (currentExecution != null
                    && currentExecution.getStatus() == ToolExecution.Status.CANCELLED)) {
                log.info("[agent] confirm blocked by cancelled ledger, conversation={}, "
                                + "confirmationTail={}", conversationId,
                        idTail(action.confirmationId()));
                confirmationService.finish(action.conversationId(), action.confirmationId());
                return replayFinished(conversationId, request.getConfirmationId(), userId, start);
            }
            log.info("[agent] confirm start, conversation={}, confirmationTail={}, approved={}, " +
                            "tool={}", conversationId, idTail(action.confirmationId()),
                    request.isApproved(), action.tool());
            return request.isApproved() ? executeConfirmed(action, userId, start) :
                    rejectConfirmed(action, start);
        });
    }

    /**
     * 查询多步骤任务进度：run 当前状态 + 每步结果。
     * 数据按 userId 隔离，不返回他人的任务。
     */
    public TaskRunDto taskStatus(String runId) {
        String userId = UserContext.require();
        TaskRun run = taskRunService.find(userId, runId)
                .orElseThrow(() -> new BizException(ErrorCode.NOT_FOUND, "任务不存在"));
        List<TaskRunDto.Step> steps = taskRunService.steps(runId).stream()
                .map(this::stepView)
                .toList();
        return new TaskRunDto(run.getId(), run.getConversationId(), run.getWorkflowId(),
                run.getStatus().name(), run.getCurrentStep(), run.getConfirmationId(),
                run.getErrorCode(), run.getErrorMessage(), run.getResult(), steps);
    }

    private TaskRunDto.Step stepView(TaskStep step) {
        Map<String, Object> args = Map.of();
        if (step.getArgsJson() != null && !step.getArgsJson().isBlank()) {
            try {
                args = JsonUtil.fromJson(step.getArgsJson(),
                        new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {
                        });
            } catch (RuntimeException ignored) {
                args = Map.of();
            }
        }
        return new TaskRunDto.Step(step.getStepNo(), step.getStageId(), step.getToolName(),
                step.getSideEffect(), args == null ? Map.of() : args, step.getStatus().name(),
                step.getResultSummary(), step.getErrorCode(), step.getErrorMessage(),
                step.getCostMs() == null ? 0L : step.getCostMs(), step.getAttempts());
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
        boolean taskCancelled = false;
        try {
            if (action.runId().isBlank()) {
                toolExecutionService.markCancelled(action.userId(), action.confirmationId());
            } else {
                // 批量确认的台账 executionId = runId:stepNo，不能用 confirmationId 取消；
                // 先取消 run，再逐个取消写步骤台账，避免留下悬空的 PENDING 记录。
                taskCancelled = taskRunService.cancel(action.userId(), action.runId());
                for (ConfirmationService.PendingWriteStep step : action.steps()) {
                    try {
                        toolExecutionService.markCancelled(action.userId(),
                                action.runId() + ":" + step.stepNo());
                    } catch (RuntimeException e) {
                        log.warn("[agent] cancel step ledger failed, runTail={}, step={}, reason={}",
                                idTail(action.runId()), step.stepNo(), e.getMessage());
                    }
                }
            }
        } catch (RuntimeException e) {
            // 台账是审计事实源，但 Redis 令牌已足以完成取消；这里降级告警，不阻断用户取消。
            log.warn("[agent] cancel ledger update failed, conversation={}, confirmationTail={}, "
                            + "reason={}", action.conversationId(), idTail(action.confirmationId()),
                    e.getMessage());
        }
        auditService.record(AuditEntry.text(action.conversationId(), "tool-confirm",
                GraphExecution.STATUS_SUCCESS, idTail(action.confirmationId()), "REJECTED", null,
                System.currentTimeMillis() - start, null));
        confirmationService.finish(action.conversationId(), action.confirmationId());
        log.info("[agent] confirm rejected, conversation={}, confirmationTail={}, costMs={}",
                action.conversationId(), idTail(action.confirmationId()),
                System.currentTimeMillis() - start);
        ChatResponse response = ChatResponse.of(action.conversationId(), answer, false, null,
                System.currentTimeMillis() - start, true, "");
        if (!action.runId().isBlank()) {
            response.setTaskRunId(action.runId());
            response.setTaskStatus(taskCancelled ? TaskRun.Status.CANCELLED.name()
                    : TaskRun.Status.RUNNING.name());
        }
        return response;
    }

    /**
     * 执行路径：执行确认图，并以数据库台账判定成败。
     *
     * <p>成功（台账 SUCCEEDED）删除令牌；失败或没有台账时保留令牌，
     * 用户可以用同一个 confirmationId 重试。重复执行由台账唯一键拦截。
     */
    private ChatResponse executeConfirmed(ConfirmationService.PendingAction action, String userId,
                                          long start) {
        String confirmQuery = "确认执行：" + action.summary();
        try {
            conversationService.saveMessage(action.conversationId(), Message.ROLE_USER,
                    confirmQuery, action.model(), null);
            WorkflowState state = invokeTaskConfirmGraph(action, userId, confirmQuery);
            // 多步骤任务以 ai_task_run 为事实源；旧版单写继续用 ai_tool_execution。
            TaskRun run = action.runId().isBlank() ? null
                    : taskRunService.find(userId, action.runId()).orElse(null);
            ToolExecution execution = action.runId().isBlank()
                    ? toolExecutionService.find(userId, action.confirmationId()).orElse(null) : null;
            if (run != null && state.result() != null && !state.result().isBlank()) {
                taskRunService.saveResult(userId, run.getId(), state.result());
                run.setResult(state.result());
            }
            boolean succeeded = run != null
                    ? run.getStatus() == TaskRun.Status.SUCCEEDED
                    : execution != null && execution.getStatus() == ToolExecution.Status.SUCCEEDED;
            // 成败以台账为准：工具失败但图正常返回时，不能把令牌当成已消费
            if (succeeded) {
                // 令牌先完成：即使后续消息落库失败，客户端用同一 ID 重试也只会拿到台账里的同一结果
                confirmationService.finish(action.conversationId(), action.confirmationId());
            }
            try {
                conversationService.saveMessage(action.conversationId(), Message.ROLE_ASSISTANT,
                        state.result(), action.model(), state.tokenUsage(),
                        snapshotContext(state.context()));
            } catch (Exception e) {
                log.warn("[agent] confirm answer save failed, conversation={}, reason={}",
                        action.conversationId(), e.getMessage());
            }
            auditConfirmOutcome(action, run, execution, start);
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
            response.setBusiness(run != null ? businessResult(run) : businessResult(execution));
            return response;
        } catch (RuntimeException e) {
            auditService.record(AuditEntry.text(action.conversationId(), "tool-confirm",
                    GraphExecution.STATUS_FAILED, idTail(action.confirmationId()), "APPROVED",
                    Throwables.rootMessage(e), System.currentTimeMillis() - start, null));
            // 保留令牌：业务失败可重试，重复执行由 ai_tool_execution 唯一键拦截
            try {
                conversationService.saveMessage(action.conversationId(), Message.ROLE_ASSISTANT,
                        "确认操作执行失败，请稍后重新发起。", action.model(), null);
            } catch (Exception ignored) {
                // 落库失败不覆盖原始执行异常
            }
            throw e;
        }
    }

    /**
     * 审计以执行台账为准：工具失败时不能把"用户点了同意"记成成功
     */
    private void auditConfirmOutcome(ConfirmationService.PendingAction action,
                                     TaskRun run, ToolExecution execution, long start) {
        boolean succeeded = run != null
                ? run.getStatus() == TaskRun.Status.SUCCEEDED
                : execution != null && execution.getStatus() == ToolExecution.Status.SUCCEEDED;
        boolean failed = !succeeded;
        String error = failed ? (run != null ? failureReason(run) : failureReason(execution)) : null;
        auditService.record(AuditEntry.text(action.conversationId(), "tool-confirm",
                failed ? GraphExecution.STATUS_FAILED : GraphExecution.STATUS_SUCCESS,
                idTail(action.confirmationId()), "APPROVED", error,
                System.currentTimeMillis() - start, null));
    }

    /**
     * 台账转业务结果：成功、无台账、执行中、失败分别给出可消费的状态。
     */
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

    /** 任务状态转业务结果：多步骤写操作以整条任务是否成功为准。 */
    private BusinessResult businessResult(TaskRun run) {
        if (run == null) {
            return BusinessResult.failure("NO_TASK_RUN", "未找到任务执行记录");
        }
        return switch (run.getStatus()) {
            case SUCCEEDED -> BusinessResult.ok();
            case RUNNING -> BusinessResult.failure("IN_PROGRESS", "任务仍在执行中");
            case PLANNING, WAITING_CONFIRM ->
                    BusinessResult.failure("NOT_EXECUTED", "任务尚未执行或仍在等待确认");
            case CANCELLED -> BusinessResult.failure("CANCELLED", "任务已取消");
            case PARTIAL_FAILED -> BusinessResult.failure("PARTIAL_FAILED",
                    blankToDefault(run.getErrorMessage(), "部分步骤执行失败"));
            case FAILED -> BusinessResult.failure(
                    blankToDefault(run.getErrorCode(), "TASK_FAILED"),
                    blankToDefault(run.getErrorMessage(), "任务执行失败"));
        };
    }

    /**
     * 审计失败原因：没有台账、执行中、执行失败分别给出可检索的错误码
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

    /** 任务失败原因：优先使用失败步骤写入的错误码。 */
    private String failureReason(TaskRun run) {
        if (run == null) {
            return "NO_TASK_RUN:未找到任务执行记录";
        }
        if (run.getStatus() == TaskRun.Status.SUCCEEDED) {
            return "";
        }
        return blankToDefault(run.getErrorCode(), "TASK_FAILED") + ":" +
                blankToDefault(run.getErrorMessage(), "任务执行失败");
    }

    /**
     * 令牌已不存在时的幂等重放：已成功返回上次结果，执行中 409，失败要求重新发起
     */
    private ChatResponse replayFinished(String conversationId, String confirmationId, String userId,
                                        long start) {
        // 多步骤任务优先按确认 ID 找回 run：它的结果快照比单个工具台账完整。
        TaskRun run = taskRunService.findByConfirmationId(userId, confirmationId).orElse(null);
        if (run != null) {
            if (!conversationId.equals(run.getConversationId())) {
                throw new BizException(ErrorCode.NOT_FOUND,
                        "确认请求不存在、已过期或已使用，请重新发起操作");
            }
            if (run.getStatus() == TaskRun.Status.SUCCEEDED) {
                log.info("[agent] task replay, conversation={}, confirmationTail={}, runTail={}",
                        conversationId, idTail(confirmationId), idTail(run.getId()));
                String result = run.getResult() == null || run.getResult().isBlank()
                        ? "该任务已执行完成。" : run.getResult();
                ChatResponse response = ChatResponse.of(conversationId, result, false, null,
                        System.currentTimeMillis() - start, true, "");
                response.setBusiness(BusinessResult.ok());
                response.setTaskRunId(run.getId());
                response.setTaskStatus(run.getStatus().name());
                return response;
            }
            if (run.getStatus() == TaskRun.Status.RUNNING) {
                throw new BizException(ErrorCode.CONFIRMATION_IN_PROGRESS);
            }
            if (run.getStatus() == TaskRun.Status.PLANNING
                    || run.getStatus() == TaskRun.Status.WAITING_CONFIRM) {
                throw new BizException(ErrorCode.NOT_FOUND,
                        "确认请求已过期且未执行，请重新发起操作");
            }
            if (run.getStatus() == TaskRun.Status.CANCELLED) {
                throw new BizException(ErrorCode.NOT_FOUND,
                        "确认请求已取消或已过期，请重新发起操作");
            }
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED,
                    "上次任务执行失败：" + failureReason(run) + "，请重新发起操作");
        }
        ToolExecution execution = toolExecutionService.find(userId, confirmationId).orElse(null);
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
     * 确认执行图入参：只带已确认计划与授权标记，不重新走 intent/task_plan
     */
    private WorkflowState invokeTaskConfirmGraph(ConfirmationService.PendingAction action,
                                                 String userId, String query) {
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put(WorkflowState.QUERY, query);
        inputs.put(WorkflowState.MODEL, action.model());
        inputs.put(WorkflowState.USER_ID, userId);
        inputs.put(WorkflowState.CONVERSATION_ID, action.conversationId());
        // 置信度透出依赖 intent=task；确认图不跑 IntentPlanNode，这里显式补齐
        inputs.put(WorkflowState.INTENT, QaIntent.TASK);
        inputs.put(WorkflowState.TASK_PLAN, action.plan());
        action.plan().stream().findFirst()
                .map(step -> step.get("workflowId"))
                .filter(value -> value != null && !String.valueOf(value).isBlank())
                .ifPresent(value -> inputs.put(WorkflowState.TASK_WORKFLOW_ID,
                        String.valueOf(value)));
        inputs.put(WorkflowState.TASK_APPROVED, true);
        inputs.put(WorkflowState.TASK_IDEMPOTENCY_KEY, action.confirmationId());
        if (!action.runId().isBlank()) {
            inputs.put(WorkflowState.TASK_RUN_ID, action.runId());
            inputs.put(WorkflowState.TASK_RUN_STATUS, TaskRun.Status.RUNNING.name());
        }
        inputs.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
        inputs.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
        RunnableConfig config = RunnableConfig.builder().threadId(action.conversationId()).build();
        long start = System.currentTimeMillis();
        return executeGraphSync(taskConfirmGraph, inputs, config, action.conversationId(), query,
                start);
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
     * <p>
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
                        Texts.textOr(item.get("errorCode"), ""), longOf(item.get("costMs")))).toList();
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
        List<ChatResponse.PendingStep> steps = new ArrayList<>();
        Object rawSteps = pending.get("steps");
        if (rawSteps instanceof List<?> list) {
            for (Object item : list) {
                Map<String, Object> map = mapOf(item);
                steps.add(new ChatResponse.PendingStep(
                        (int) longOf(map.get("stepNo")),
                        Texts.textOr(map.get("tool"), ""),
                        mapOf(map.get("args"))));
            }
        }
        return new ChatResponse.PendingAction(confirmationId, Texts.textOr(pending.get("tool"), ""),
                mapOf(pending.get("args")), Texts.textOr(pending.get("summary"), ""),
                Texts.textOr(pending.get("risk"), ""), longOf(pending.get("expiresAt")),
                Texts.textOr(pending.get("runId"), state.taskRunId()), steps);
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

    @SuppressWarnings("unchecked")
    /** 把对象安全转成字符串键 Map；类型不符返回空 Map。 */ private Map<String, Object> mapOf(
            Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

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
     * @param userId         当前用户 ID（需要按用户隔离数据的节点使用）
     * @param query          原始用户输入
     * @param model          本轮模型名（已解析）
     * @return 图执行最终状态
     */
    private WorkflowState prepareAndInvoke(String conversationId, String userId, String query,
                                           String model, String answerMode, String visibility) {
        PreparedTurn turn = prepareTurn(conversationId, query, model);
        return invokeGraph(conversationId, userId, query, turn.rewrite().rewritten(),
                turn.history(), turn.summary(), model, answerMode, visibility);
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
        // 步骤4：改写 + 话题归属（一次 LLM 调用；内部无历史短路/LLM 失败降级），审计落库便于排查
        long rewriteStart = System.currentTimeMillis();
        RewriteResult rewrite = queryRewriter.rewrite(query, rewriteView, topics, model);
        long rewriteCostMs = System.currentTimeMillis() - rewriteStart;
        // 步骤5：话题段归属：命中旧段复用（切回任务A只读A的窗口+摘要），新话题新建段
        ConversationSummary segment =
                segmentService.resolveSegment(conversationId, segments, rewrite,
                        userMessage.getId());
        // 步骤5.5：回填本轮 user 消息的段归属（assistant 落库时继承），生成视图按段号精确过滤防混段
        conversationService.updateMessageSegment(userMessage.getId(), segment.getSegmentId());
        // 步骤5.6：改写审计（结构化保留原句、改写句、话题归属与段号，排查语义漂移）
        Map<String, Object> rewriteInput = new LinkedHashMap<>();
        rewriteInput.put("query", query);
        rewriteInput.put("historyCount", rewriteView.size());
        rewriteInput.put("topics", topics);
        Map<String, Object> rewriteOutput = new LinkedHashMap<>();
        rewriteOutput.put("rewritten", rewrite.rewritten());
        rewriteOutput.put("topic", rewrite.topic());
        rewriteOutput.put("newTopic", rewrite.newTopic());
        rewriteOutput.put("segmentId", segment.getSegmentId());
        auditService.record(AuditEntry.of(conversationId, "rewrite",
                GraphExecution.STATUS_SUCCESS, rewriteInput, rewriteOutput, null, rewriteCostMs,
                null));
        // 步骤6：生成视图（当前话题段内原文，只给生成 LLM；token 预算装配，排除当前消息）
        List<Map<String, String>> generationView =
                conversationService.generationHistoryView(conversationId, segment.getSegmentId(),
                        properties.getHistory().getGenerationMaxTokens(),
                        properties.getHistory().getGenerationItemMaxLength(),
                        properties.getLimits().getGenerationCandidateLimit());
        // 步骤7：异步触发段摘要（锁内仅提交任务，不等待 LLM 执行）
        summaryService.triggerIfNeeded(conversationId, segment);
        String summary = segment.getContent() == null ? "" : segment.getContent();
        log.info("[agent] turn prepared, conversation={}, segment={}, rewritten={}, history={}, " +
                        "summaryLength={}", conversationId, segment.getSegmentId(),
                rewrite.rewritten() != null && !rewrite.rewritten().isBlank(),
                generationView.size(), summary.length());
        return new PreparedTurn(rewrite, generationView, summary);
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
                                            List<Map<String, String>> history, String summary,
                                            String model, String userId, String conversationId,
                                            String answerMode, String visibility) {
        return Map.of(WorkflowState.QUERY, query, WorkflowState.MODEL, model,
                WorkflowState.REWRITTEN_QUERY, rewritten == null ? "" : rewritten,
                WorkflowState.HISTORY, history == null ? List.of() : history, WorkflowState.SUMMARY,
                summary == null ? "" : summary, WorkflowState.USER_ID, userId,
                WorkflowState.CONVERSATION_ID, conversationId, WorkflowState.ANSWER_MODE,
                answerMode, WorkflowState.VISIBILITY, visibility);
    }

    /**
     * 图执行（同步路径）：提交专用线程池 + 整体超时中止 + 异常转换为业务错误码。
     * <p>
     * 超时策略：future.cancel(true) 尽力中断工作线程，本轮改返回降级应答
     * （user + assistant 照常落库），一问一答闭环；cancel 返回 false 说明任务恰在
     * 超时点完成，直接取回结果，不浪费本轮执行。
     *
     * @param conversationId 会话 ID（threadId，checkpoint 隔离维度）
     * @param userId         当前用户 ID（入图注入）
     * @param query          原始用户输入（审计用）
     * @param rewritten      改写后的自包含句（图内实际使用）
     * @param history        生成视图（当前话题段内原文，role/content 键值对）
     * @param summary        当前话题段摘要（可为空）
     * @param model          本轮模型名（已解析）
     * @return 图执行最终状态；超时返回降级 WorkflowState（fallback=true）
     */
    private WorkflowState invokeGraph(String conversationId, String userId, String query,
                                      String rewritten, List<Map<String, String>> history,
                                      String summary, String model, String answerMode,
                                      String visibility) {
        long start = System.currentTimeMillis();
        RunnableConfig config = RunnableConfig.builder().threadId(conversationId).build();
        Map<String, Object> inputs =
                buildInputs(query, rewritten, history, summary, model, userId, conversationId,
                        answerMode, visibility);
        WorkflowState state =
                executeGraphWithTimeout(agentGraph, inputs, config, conversationId, query, start);
        auditRetrievalPlan(conversationId, query, state);
        return state;
    }

    /**
     * 统一的图执行入口：提交专用线程池 + 整体超时 + 超时取消 + 结果/异常审计。
     * <p>
     * 只执行一次，不做 catch 后重跑：写工具、模型调用都可能产生副作用，
     * 盲目重跑会重复执行工具或重复计费。超时取消失败说明任务刚好完成，再取回结果即可。
     */
    private WorkflowState executeGraphWithTimeout(CompiledGraph<WorkflowState> graph,
                                                  Map<String, Object> inputs, RunnableConfig config,
                                                  String conversationId, String query, long start) {
        long timeoutSeconds = Math.max(10, properties.getExecutionTimeoutSeconds());
        Future<WorkflowState> future = submitGraph(graph, inputs, config, conversationId);
        try {
            return awaitGraph(future, timeoutSeconds, conversationId, query, start);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行被中断");
        }
    }

    /**
     * 确认图同步执行：由持有严格会话锁的线程直接跑完。
     * <p>确认写操作不能把执行提交到其他线程后再由请求线程释放锁，否则超时会出现
     * “锁已释放、工作线程仍在写数据库”的生命周期错位。
     */
    private WorkflowState executeGraphSync(CompiledGraph<WorkflowState> graph,
                                           Map<String, Object> inputs, RunnableConfig config,
                                           String conversationId, String query, long start) {
        try {
            return completeGraph(graph.invoke(inputs, config).orElse(null), conversationId, query,
                    start);
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            throw graphFailure(e, conversationId, query, start);
        }
    }

    /**
     * 提交到专用线程池；队列满直接 429，避免请求在队列里等到超时。
     */
    private Future<WorkflowState> submitGraph(CompiledGraph<WorkflowState> graph,
                                              Map<String, Object> inputs, RunnableConfig config,
                                              String conversationId) {
        try {
            return agentExecutor.submit(() -> graph.invoke(inputs, config).orElse(null));
        } catch (RejectedExecutionException e) {
            log.warn("[agent] executor overloaded, reject request, thread={}", conversationId);
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }
    }

    /**
     * 等待结果；超时、取消、异常归一由 {@link BoundedCall} 统一处理。
     *
     * <p>提交阶段仍由 {@link #submitGraph} 负责队列满转 429；这里只把通用 Outcome
     * 翻译成图执行语义：成功完成、超时降级、中断、失败。
     */
    private WorkflowState awaitGraph(Future<WorkflowState> future, long timeoutSeconds,
                                     String conversationId, String query,
                                     long start) throws InterruptedException {
        BoundedCall.Outcome<WorkflowState> outcome =
                BoundedCall.await(future, timeoutSeconds * 1000L);
        if (outcome.status() == BoundedCall.Status.SUCCESS) {
            if (outcome.timedOut()) {
                log.info("[agent] execution completed just after timeout, thread={}",
                        conversationId);
            }
            return completeGraph(outcome.value(), conversationId, query, start);
        }
        if (outcome.status() == BoundedCall.Status.INTERRUPTED) {
            throw new InterruptedException("图执行被中断");
        }
        if (outcome.status() == BoundedCall.Status.FAILED) {
            throw graphFailure(outcome.error(), conversationId, query, start);
        }
        if (outcome.status() == BoundedCall.Status.REJECTED) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }

        // TIMEOUT：cancel 成功，工作线程已停止；返回降级应答保证一问一答闭环。
        long costMs = System.currentTimeMillis() - start;
        auditService.record(AuditEntry.text(conversationId, "workflow",
                GraphExecution.STATUS_FAILED, query, null, "执行超时已中止", costMs, null));
        metrics.recordGraphDuration(costMs);
        log.warn("[agent] execution timeout after {}s, thread={}, task cancelled, return fallback answer",
                timeoutSeconds, conversationId);
        return timeoutFallback();
    }

    /**
     * 成功完成：统一空结果检查、耗时指标和 workflow 审计。
     */
    private WorkflowState completeGraph(WorkflowState state, String conversationId, String query,
                                        long start) {
        long costMs = System.currentTimeMillis() - start;
        if (state == null) {
            auditService.record(AuditEntry.text(conversationId, "workflow",
                    GraphExecution.STATUS_FAILED, query, null, "图执行未返回状态", costMs, null));
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行未返回结果");
        }
        metrics.recordGraphDuration(costMs);
        auditService.record(AuditEntry.text(conversationId, "workflow",
                GraphExecution.STATUS_SUCCESS, query, state.result(), null, costMs,
                state.rootCause()));
        return state;
    }

    /**
     * 失败统一转换：BizException 原样上抛，其他异常落审计后转 AGENT_EXECUTION_FAILED。
     */
    private BizException graphFailure(Throwable cause, String conversationId, String query,
                                      long start) {
        auditService.record(AuditEntry.text(conversationId, "workflow",
                GraphExecution.STATUS_FAILED, query, null, Throwables.rootMessage(cause),
                System.currentTimeMillis() - start, null));
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
     * 检索计划审计：记录最终使用的主查询/子查询、检索深度和证据结果。
     * 只对知识问答且有检索深度的轮次写，避免闲聊/任务分支产生无意义记录。
     */
    private void auditRetrievalPlan(String conversationId, String query, WorkflowState state) {
        if (!QaIntent.isKnowledge(state.intent()) || state.retrievalDepth().isBlank()) {
            return;
        }
        WorkflowState.QueryPlan plan = state.queryPlan();
        WorkflowState.RetrievalEvidence evidence = state.retrievalEvidence();
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("query", query);
        input.put("effectiveQuery", state.effectiveQuery());
        Map<String, Object> output = new LinkedHashMap<>();
        output.put("primaryQuery", plan.primaryQuery());
        output.put("subQueries", plan.subQueries());
        output.put("complexity", plan.complexity());
        output.put("risk", plan.risk());
        output.put("retrievalDepth", state.retrievalDepth());
        output.put("requery", "requery".equals(state.retrievalDepth()));
        output.put("contextLength", state.context() == null ? 0 : state.context().length());
        output.put("hitCount", evidence.hitCount());
        output.put("confidence", evidence.confidence());
        output.put("sufficient", evidence.sufficient());
        output.put("rootCause", state.rootCause());
        auditService.record(AuditEntry.of(conversationId, "retrieval-plan",
                GraphExecution.STATUS_SUCCESS, input, output, null, null, null));
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
                                String summary) {
    }
}
