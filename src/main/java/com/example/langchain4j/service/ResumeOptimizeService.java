package com.example.langchain4j.service;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.ResumeChangeDto;
import com.example.langchain4j.dto.ResumeDiffBlockDto;
import com.example.langchain4j.dto.ResumeOptimizeRequest;
import com.example.langchain4j.dto.ResumeOptimizeResponse;
import com.example.langchain4j.dto.ResumeRevisionSummary;
import com.example.langchain4j.dto.ResumeTargetJobDto;
import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import com.example.langchain4j.graph.resume.model.ResumeOptimizeMode;
import com.example.langchain4j.graph.resume.model.ResumePromptPresets;
import com.example.langchain4j.graph.resume.model.ResumeTargetJob;
import com.example.langchain4j.graph.resume.util.ResumeSectionNormalizer;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.guard.SessionLock;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.entity.ResumeRevision;
import com.example.langchain4j.repository.entity.UserResume;
import com.example.langchain4j.repository.mapper.ResumeRevisionMapper;
import com.example.langchain4j.repository.mapper.UserResumeMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.RunnableConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 简历优化服务：两条触发路径，共用一个执行体。
 * <ul>
 *   <li>独立接口（{@link #optimize}）：同步等结果，锁会话优先（请求带 conversationId）、
 *       用户级兜底，防止同一会话/同一用户并发跑多份简历；落草稿前与对话触发共用触发短锁查重；</li>
 *   <li>对话触发（{@link #triggerAsync}）：由问答图 resume_task 节点调用，落 RUNNING 草稿后
 *       提交后台异步执行，触发轮照常闭环；长任务不持会话锁，防重靠 RUNNING 状态 + 触发短锁。</li>
 * </ul>
 * 后台任务完成后向触发会话回写一条 assistant 通知（conversationId 非空时）。
 * <p>
 * 失败只返回明确错误，不返回半份简历。
 */
@Slf4j
@Service
public class ResumeOptimizeService {

    private static final int MAX_LIST_LIMIT = 100;
    private static final int MAX_ERROR_CHARS = 1_000;
    /** 对话触发（FREE 模式）的默认优化提示词 */
    private static final String DEFAULT_CHAT_PROMPT =
            "整体优化简历：修正措辞与结构、突出亮点、保持事实不变。";
    /** 防重短锁前缀：两条触发路径（对话 triggerAsync / 同步 optimize）共用，只锁「查重 + 落 RUNNING 草稿」这一小段（毫秒级），长任务执行不持锁 */
    private static final String TRIGGER_LOCK_PREFIX = "resume-trigger:";

    private final CompiledGraph<ResumeOptimizeState> resumeOptimizeGraph;
    private final RateLimiter rateLimiter;
    private final SessionLock sessionLock;
    private final ModelRegistry modelRegistry;
    private final AgentProperties properties;
    private final AgentMetrics metrics;
    private final AuditService auditService;
    private final ResumeRevisionMapper resumeRevisionMapper;
    private final UserResumeMapper userResumeMapper;
    private final ConversationService conversationService;
    private final ThreadPoolTaskExecutor agentExecutor;

    public ResumeOptimizeService(@Qualifier("resumeOptimizeGraph") CompiledGraph<ResumeOptimizeState> resumeOptimizeGraph,
                                 RateLimiter rateLimiter,
                                 SessionLock sessionLock,
                                 ModelRegistry modelRegistry,
                                 AgentProperties properties,
                                 AgentMetrics metrics,
                                 AuditService auditService,
                                 ResumeRevisionMapper resumeRevisionMapper,
                                 UserResumeMapper userResumeMapper,
                                 ConversationService conversationService,
                                 @Qualifier("agentExecutor") ThreadPoolTaskExecutor agentExecutor) {
        this.resumeOptimizeGraph = resumeOptimizeGraph;
        this.rateLimiter = rateLimiter;
        this.sessionLock = sessionLock;
        this.modelRegistry = modelRegistry;
        this.properties = properties;
        this.metrics = metrics;
        this.auditService = auditService;
        this.resumeRevisionMapper = resumeRevisionMapper;
        this.userResumeMapper = userResumeMapper;
        this.conversationService = conversationService;
        this.agentExecutor = agentExecutor;
    }

    // ---- 独立接口：同步优化 ----

    /**
     * 执行一次简历优化（同步等待结果）。
     * <p>
     * 锁粒度：请求携带 conversationId 时锁会话（与对话触发同一 key 空间，同会话串行）；
     * 未携带时退化为用户级锁兜底（独立页面直传简历的场景，防止并发打爆 LLM 配额）。
     * 落草稿前与对话触发（triggerAsync）共用触发短锁查重：已有 RUNNING 时直接拒绝，
     * 阻断两条路径并发双跑、双倍消耗 LLM 配额。
     *
     * @param request 请求参数
     * @return 改稿 + 变更清单 + diff
     */
    public ResumeOptimizeResponse optimize(ResumeOptimizeRequest request) {
        String userId = UserContext.require();
        rateLimiter.checkAcquire(userId);
        String model = modelRegistry.resolve(request.getModel());

        ResumeOptimizeMode mode = ResumeOptimizeMode.from(request.getMode());
        if (mode == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "不支持的优化模式：" + request.getMode() + "，可选 TARGETED / FREE");
        }
        if (request.getResumeText() == null || request.getResumeText().isBlank()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "简历原文不能为空");
        }

        List<ResumeTargetJob> jobs = List.of();
        String prompt = "";
        if (mode == ResumeOptimizeMode.TARGETED) {
            jobs = validateTargets(request.getTargets());
        } else {
            prompt = request.getOptimizationPrompt() == null
                    ? "" : request.getOptimizationPrompt().trim();
            if (prompt.isBlank()) {
                throw new BizException(ErrorCode.BAD_REQUEST, "自由优化必须填写优化提示词");
            }
            validateSelectedSections(request.getResumeText(), request.getSelectedSections());
        }

        String revisionId = newRevisionId();
        String title = resolveTitle(request.getResumeTitle());
        String targetJson = buildTargetJson(mode, jobs, request.getSelectedSections(), prompt);

        ResumeRevision draft = new ResumeRevision();
        draft.setRevisionId(revisionId);
        draft.setUserId(userId);
        draft.setConversationId(request.getConversationId());
        draft.setResumeTitle(title);
        draft.setMode(mode.name());
        draft.setTargetJson(targetJson);
        draft.setOriginalMd(request.getResumeText());
        draft.setStatus(ResumeRevision.STATUS_RUNNING);
        draft.setModel(model);
        // 防重与对话触发（triggerAsync）共用同一把短锁 + 同一查重语义：
        // 「查重 + 落 RUNNING 草稿」原子执行，两条路径并发时最多只有一份任务进入执行
        sessionLock.executeWithLock(TRIGGER_LOCK_PREFIX + userId, () -> {
            if (resumeRevisionMapper.selectRunningByUser(userId) != null) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "简历优化进行中，请稍后再试");
            }
            resumeRevisionMapper.insert(draft);
            return null;
        });

        long start = System.currentTimeMillis();
        String auditInput = auditInput(mode, jobs.size(),
                request.getResumeText().length(), request.getSelectedSections());
        // lambda 捕获需要 effectively final：jobs/prompt 在模式分支里重新赋值过
        List<ResumeTargetJob> finalJobs = jobs;
        String finalPrompt = prompt;
        try {
            ResumeOptimizeState state = sessionLock.executeWithLock(
                    lockKey(request.getConversationId(), userId),
                    () -> invokeGraph(revisionId, request.getResumeText(),
                            request.getSelectedSections(), mode, finalJobs, finalPrompt, model));
            metrics.recordGraphDuration(System.currentTimeMillis() - start);

            if (state.fallback() || state.revisedMd() == null || state.revisedMd().isBlank()) {
                String error = state.error() == null || state.error().isBlank()
                        ? "简历优化未产出有效改稿" : state.error();
                auditService.audit(revisionId, "resume_workflow", GraphExecution.STATUS_FAILED,
                        auditInput, null, error, System.currentTimeMillis() - start);
                throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, error);
            }

            String changesJson = JsonUtil.toJson(state.changes());
            String diffJson = JsonUtil.toJson(state.diff());
            resumeRevisionMapper.markDone(revisionId, state.revisedMd(), changesJson, diffJson,
                    model, state.tokenUsage());
            auditService.audit(revisionId, "resume_workflow", GraphExecution.STATUS_SUCCESS,
                    auditInput, "revisedChars=" + state.revisedMd().length(),
                    null, System.currentTimeMillis() - start);
            log.info("[resume-optimize] success, revisionId={}, mode={}, revisedChars={}, tokens={}",
                    revisionId, mode, state.revisedMd().length(), state.tokenUsage());
            return toResponse(state, revisionId, title, model, mode, null);
        } catch (BizException e) {
            markErrorQuietly(revisionId, e.getMessage());
            throw e;
        } catch (Exception e) {
            markErrorQuietly(revisionId, e.getMessage());
            log.error("[resume-optimize] failed, revisionId={}", revisionId, e);
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED,
                    "简历优化失败：" + rootMessage(e));
        }
    }

    // ---- 对话触发：异步执行 ----

    /**
     * 对话内触发简历优化（resume_task 节点调用）：查档案 → 防重 → 落草稿 → 提交后台任务。
     * <p>
     * 返回给用户的提示语由节点直接作为本轮回答（不调 LLM，不进入简历图）。
     * 后台任务不持会话锁：长任务执行期间用户照常对话；防重靠 RUNNING 状态 + 触发短锁，
     * 同一用户同时只允许一份在跑。
     *
     * @param userId         当前用户
     * @param conversationId 触发会话（可空：空则完成后不回写通知）
     * @param model          本轮对话解析出的模型名
     * @return 提示语（已触发 / 档案缺失引导 / 已有任务进行中 / 系统繁忙）
     */
    public String triggerAsync(String userId, String conversationId, String model) {
        UserResume profile = userResumeMapper.selectByUserId(userId);
        if (profile == null || profile.getContent() == null || profile.getContent().isBlank()) {
            return "还没有找到你的简历。请先在简历优化页面上传简历，再对我说「帮我优化简历」。";
        }
        // 触发短锁：只包「查重 + 落草稿 + 提交」这一段，防同用户多会话并发触发双份任务
        return sessionLock.executeWithLock(TRIGGER_LOCK_PREFIX + userId, () -> {
            ResumeRevision running = resumeRevisionMapper.selectRunningByUser(userId);
            if (running != null) {
                return "你的简历优化正在进行中，完成后我会通知你。";
            }
            String revisionId = newRevisionId();
            String title = resolveTitle(profile.getTitle());
            String targetJson = buildTargetJson(
                    ResumeOptimizeMode.FREE, List.of(), List.of(), DEFAULT_CHAT_PROMPT);

            ResumeRevision draft = new ResumeRevision();
            draft.setRevisionId(revisionId);
            draft.setUserId(userId);
            draft.setConversationId(conversationId);
            draft.setResumeTitle(title);
            draft.setMode(ResumeOptimizeMode.FREE.name());
            draft.setTargetJson(targetJson);
            draft.setOriginalMd(profile.getContent());
            draft.setStatus(ResumeRevision.STATUS_RUNNING);
            draft.setModel(model);
            resumeRevisionMapper.insert(draft);

            try {
                agentExecutor.execute(() -> executeAndNotify(
                        revisionId, conversationId, profile.getContent(), title, model));
            } catch (RejectedExecutionException e) {
                // 线程池满：落 ERROR 并如实告知，用户可稍后重试（不持锁重试）
                markErrorQuietly(revisionId, "系统繁忙，请稍后重试");
                log.warn("[resume-optimize] trigger rejected, revisionId={}", revisionId);
                return "系统繁忙，简历优化未能启动，请稍后重试。";
            }
            log.info("[resume-optimize] triggered from chat, revisionId={}, conversationId={}",
                    revisionId, conversationId);
            return "已开始优化你的简历（" + title + "），完成后我会通知你。";
        });
    }

    /**
     * 后台执行体（agentExecutor 线程，不持锁）：跑图 → 落结果 → 会话通知。
     * 与独立接口共用一个执行语义：失败只记 ERROR，不返回半份简历。
     * <p>
     * 图在当前线程直接执行（已在 agentExecutor 上，不再向同池 submit）：
     * 若再走「submit + future.get」会在池满时与图任务互相等待形成准死锁；
     * 后台任务无 HTTP 线程等待，整体超时由 LLM 网关层与图内重试上限兜底。
     */
    private void executeAndNotify(String revisionId, String conversationId,
                                  String resumeText, String title, String model) {
        long start = System.currentTimeMillis();
        try {
            ResumeOptimizeState state = invokeGraphDirect(revisionId, resumeText, null,
                    ResumeOptimizeMode.FREE, List.<ResumeTargetJob>of(), DEFAULT_CHAT_PROMPT, model);
            if (state.fallback() || state.revisedMd() == null || state.revisedMd().isBlank()) {
                String error = state.error() == null || state.error().isBlank()
                        ? "简历优化未产出有效改稿" : state.error();
                markErrorQuietly(revisionId, error);
                notifyCompletion(conversationId, model, null,
                        "简历优化未完成：" + error + "。请稍后在简历优化页面重试。");
                return;
            }
            String changesJson = JsonUtil.toJson(state.changes());
            String diffJson = JsonUtil.toJson(state.diff());
            resumeRevisionMapper.markDone(revisionId, state.revisedMd(), changesJson, diffJson,
                    model, state.tokenUsage());
            auditService.audit(revisionId, "resume_workflow", GraphExecution.STATUS_SUCCESS,
                    "mode=FREE, inputChars=" + resumeText.length(),
                    "revisedChars=" + state.revisedMd().length(),
                    null, System.currentTimeMillis() - start);
            notifyCompletion(conversationId, model, state.tokenUsage(),
                    "简历优化已完成（" + title + "）。可以在简历优化记录中查看改稿与变更对比，确认后可保存。");
        } catch (Exception e) {
            markErrorQuietly(revisionId, e.getMessage());
            log.error("[resume-optimize] async failed, revisionId={}", revisionId, e);
            notifyCompletion(conversationId, model, null,
                    "简历优化未完成，请稍后在简历优化页面重试。");
        }
    }

    /** 完成后向触发会话回写一条 assistant 通知（conversationId 为空或落库失败时静默跳过，不阻断） */
    private void notifyCompletion(String conversationId, String model, Integer tokenUsage, String content) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }
        try {
            conversationService.saveMessage(
                    conversationId, Message.ROLE_ASSISTANT, content, model, tokenUsage);
        } catch (Exception e) {
            log.warn("[resume-optimize] notify failed, conversationId={}: {}", conversationId, e.getMessage());
        }
    }

    // ---- 简历档案 ----

    /** 上传/更新简历档案（每用户仅一份，覆盖式） */
    public void saveProfile(String title, String content) {
        String userId = UserContext.require();
        UserResume resume = new UserResume();
        resume.setUserId(userId);
        resume.setTitle(resolveTitle(title));
        resume.setContent(content.trim());
        userResumeMapper.upsert(resume);
        log.info("[resume-profile] saved, userId={}, chars={}", userId, content.trim().length());
    }

    /** 查询当前用户简历档案（无档案返回 null） */
    public UserResume getProfile() {
        return userResumeMapper.selectByUserId(UserContext.require());
    }

    // ---- 查询与保存标记 ----

    /** 查询优化详情（校验归属） */
    public ResumeOptimizeResponse getRevision(String revisionId) {
        ResumeRevision revision = requireOwned(revisionId);
        return toResponse(revision);
    }

    /** 当前用户的历史记录（只返回元信息） */
    public List<ResumeRevisionSummary> listRevisions(int limit) {
        String userId = UserContext.require();
        int bounded = Math.min(Math.max(limit, 1), MAX_LIST_LIMIT);
        return resumeRevisionMapper.selectByUser(userId, bounded).stream()
                .map(revision -> new ResumeRevisionSummary(
                        revision.getRevisionId(), revision.getResumeTitle(), revision.getMode(),
                        revision.getStatus(), revision.getModel(), revision.getTokenUsage(),
                        revision.getCreatedAt()))
                .toList();
    }

    /** 用户确认保存：只允许 DONE 记录置为 SAVED（幂等） */
    public void markSaved(String revisionId) {
        ResumeRevision revision = requireOwned(revisionId);
        if (ResumeRevision.STATUS_SAVED.equals(revision.getStatus())) {
            return;
        }
        if (!ResumeRevision.STATUS_DONE.equals(revision.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "当前记录不可保存（仅 DONE 状态可保存）");
        }
        resumeRevisionMapper.markSaved(revisionId);
    }

    /** 自由优化预设提示词 */
    public Map<String, List<ResumePromptPresets.Preset>> presets() {
        return ResumePromptPresets.all();
    }

    // ---- 内部实现 ----

    /**
     * 图执行（同步路径）：提交专用线程池 + 整体超时中止 + 异常转换为业务错误码。
     * 与问答图 {@link AgentService#invokeGraph} 同一套执行样板：超时取消后 cancel 返回 false
     * 说明任务恰在超时点完成，取回结果不浪费本轮执行。
     */
    private ResumeOptimizeState invokeGraph(String revisionId, String resumeText,
                                            List<String> selectedSections,
                                            ResumeOptimizeMode mode, List<ResumeTargetJob> jobs,
                                            String prompt, String model) {
        RunnableConfig config = RunnableConfig.builder().threadId(revisionId).build();
        int timeoutSeconds = Math.max(10, properties.getResume().getTimeoutSeconds());
        Future<ResumeOptimizeState> future;
        try {
            future = agentExecutor.getThreadPoolExecutor().submit(
                    () -> resumeOptimizeGraph.invoke(buildInputs(revisionId, resumeText,
                            selectedSections, mode, jobs, prompt, model), config).orElse(null));
        } catch (RejectedExecutionException e) {
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS, "系统繁忙，请稍后重试");
        }

        try {
            ResumeOptimizeState state = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (state == null) {
                throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行未返回结果");
            }
            return state;
        } catch (TimeoutException e) {
            if (future.cancel(true)) {
                log.warn("[resume-optimize] timeout after {}s, task cancelled, revisionId={}",
                        timeoutSeconds, revisionId);
                throw new BizException(ErrorCode.AGENT_TIMEOUT, "简历优化超时，请稍后重试");
            }
            try {
                ResumeOptimizeState completed = future.get();
                if (completed == null) {
                    throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行未返回结果");
                }
                return completed;
            } catch (Exception ex) {
                throw new BizException(ErrorCode.AGENT_TIMEOUT, "简历优化超时，请稍后重试");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "简历优化被中断");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() == null ? e : e.getCause();
            if (cause instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED,
                    "简历优化失败：" + rootMessage(cause));
        }
    }

    /**
     * 图执行（后台异步路径）：当前线程直接执行，不做整体超时包装。
     * 调用方（executeAndNotify）已在 agentExecutor 线程上，再向同池提交会在池满时
     * 与图任务互相等待形成准死锁；超时保护由 LLM 网关（单次 60s）与图内重试上限兜底。
     */
    private ResumeOptimizeState invokeGraphDirect(String revisionId, String resumeText,
                                                  List<String> selectedSections,
                                                  ResumeOptimizeMode mode, List<ResumeTargetJob> jobs,
                                                  String prompt, String model) {
        RunnableConfig config = RunnableConfig.builder().threadId(revisionId).build();
        ResumeOptimizeState state = resumeOptimizeGraph.invoke(buildInputs(revisionId, resumeText,
                selectedSections, mode, jobs, prompt, model), config).orElse(null);
        if (state == null) {
            throw new BizException(ErrorCode.AGENT_EXECUTION_FAILED, "图执行未返回结果");
        }
        return state;
    }

    /** 图输入装配：两条执行路径共用，避免字段重复漂移 */
    private Map<String, Object> buildInputs(String revisionId, String resumeText,
                                            List<String> selectedSections,
                                            ResumeOptimizeMode mode, List<ResumeTargetJob> jobs,
                                            String prompt, String model) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(ResumeOptimizeState.RESUME_RAW, resumeText);
        inputs.put(ResumeOptimizeState.MODE, mode.name());
        inputs.put(ResumeOptimizeState.TARGET_JOBS,
                jobs.stream().map(ResumeTargetJob::toMap).toList());
        inputs.put(ResumeOptimizeState.SELECTED_SECTIONS,
                selectedSections == null ? List.of() : selectedSections);
        inputs.put(ResumeOptimizeState.OPTIMIZATION_PROMPT, prompt);
        inputs.put(ResumeOptimizeState.MODEL, model);
        return inputs;
    }

    /** 锁 key：会话优先（与对话触发同一 key 空间，同会话串行）；无会话时用户级兜底（防配额打爆） */
    private String lockKey(String conversationId, String userId) {
        return conversationId == null || conversationId.isBlank()
                ? "resume:" + userId : conversationId;
    }

    private String newRevisionId() {
        return "rv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private List<ResumeTargetJob> validateTargets(List<ResumeTargetJobDto> targets) {
        if (targets == null || targets.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "定向优化至少需要 1 个目标岗位");
        }
        List<ResumeTargetJob> jobs = new ArrayList<>(targets.size());
        for (ResumeTargetJobDto dto : targets) {
            ResumeTargetJob job = new ResumeTargetJob(
                    dto.getJobName(), dto.getCompany(), dto.getSalary(), dto.getJd(),
                    dto.getSkills(), dto.getLabels());
            if (job.jobName().isBlank() && job.jd().isBlank()
                    && job.skills().isEmpty() && job.labels().isEmpty()) {
                throw new BizException(ErrorCode.BAD_REQUEST,
                        "目标岗位信息为空：至少提供职位名、JD、技能或标签之一");
            }
            jobs.add(job);
        }
        return jobs;
    }

    private void validateSelectedSections(String resumeText, List<String> selectedSections) {
        if (selectedSections == null || selectedSections.isEmpty()) {
            return;
        }
        List<String> availableTitles = ResumeSectionNormalizer.extractSectionTitles(resumeText);
        if (availableTitles.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "未识别到简历章节标题，无法按章节优化；可将 selectedSections 留空表示全部章节");
        }
        var available = ResumeSectionNormalizer.headingSet(resumeText);
        List<String> missing = selectedSections.stream()
                .filter(section -> !available.contains(ResumeSectionNormalizer.normalizeTitle(section)))
                .toList();
        if (!missing.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "选定章节不存在：" + String.join("、", missing)
                            + "；可选章节：" + String.join("、", availableTitles));
        }
    }

    private String resolveTitle(String title) {
        return title == null || title.isBlank() ? "未命名简历" : title.trim();
    }

    private String buildTargetJson(ResumeOptimizeMode mode, List<ResumeTargetJob> jobs,
                                   List<String> selectedSections, String prompt) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("mode", mode.name());
        if (mode == ResumeOptimizeMode.TARGETED) {
            snapshot.put("jobs", jobs.stream().map(ResumeTargetJob::toMap).toList());
        } else {
            snapshot.put("selectedSections",
                    selectedSections == null ? List.of() : selectedSections);
            snapshot.put("optimizationPrompt", prompt);
        }
        return JsonUtil.toJson(snapshot);
    }

    /** 审计摘要只记录规模指标，不写简历标题/正文，避免个人信息进审计表 */
    private String auditInput(ResumeOptimizeMode mode, int jobCount,
                              int inputChars, List<String> selectedSections) {
        return "mode=" + mode + ", jobs=" + jobCount
                + ", inputChars=" + inputChars
                + ", selectedSections=" + (selectedSections == null ? 0 : selectedSections.size());
    }

    private ResumeRevision requireOwned(String revisionId) {
        String userId = UserContext.require();
        ResumeRevision revision = resumeRevisionMapper.selectByRevisionId(revisionId);
        if (revision == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "简历优化记录不存在：" + revisionId);
        }
        if (!userId.equals(revision.getUserId())) {
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该简历优化记录");
        }
        return revision;
    }

    private void markErrorQuietly(String revisionId, String error) {
        try {
            String bounded = error == null ? "未知错误"
                    : (error.length() <= MAX_ERROR_CHARS ? error : error.substring(0, MAX_ERROR_CHARS));
            resumeRevisionMapper.markError(revisionId, bounded);
        } catch (Exception e) {
            // 落库失败不覆盖原始业务异常，只告警
            log.warn("[resume-optimize] mark error failed, revisionId={}: {}", revisionId, e.getMessage());
        }
    }

    private ResumeOptimizeResponse toResponse(ResumeOptimizeState state, String revisionId,
                                              String title, String model, ResumeOptimizeMode mode,
                                              String originalMarkdown) {
        List<ResumeChangeDto> changes = state.changes().stream()
                .map(ResumeChangeDto::fromMap).toList();
        List<ResumeDiffBlockDto> diff = state.diff().stream()
                .map(ResumeDiffBlockDto::fromMap).toList();
        return new ResumeOptimizeResponse(
                revisionId, title, mode.name(), model,
                state.fallback() ? ResumeRevision.STATUS_ERROR : ResumeRevision.STATUS_DONE,
                originalMarkdown, state.revisedMd(), changes, diff,
                state.tokenUsage(), state.truncated(), state.error(), OffsetDateTime.now());
    }

    private ResumeOptimizeResponse toResponse(ResumeRevision revision) {
        List<ResumeChangeDto> changes = parseList(revision.getChangesJson()).stream()
                .map(ResumeChangeDto::fromMap).toList();
        List<ResumeDiffBlockDto> diff = parseList(revision.getDiffJson()).stream()
                .map(ResumeDiffBlockDto::fromMap).toList();
        return new ResumeOptimizeResponse(
                revision.getRevisionId(), revision.getResumeTitle(), revision.getMode(),
                revision.getModel(), revision.getStatus(), revision.getOriginalMd(),
                revision.getRevisedMd(), changes, diff, revision.getTokenUsage(),
                false, revision.getError(), revision.getCreatedAt());
    }

    private List<Map<String, Object>> parseList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> parsed = JsonUtil.fromJson(json,
                    new TypeReference<List<Map<String, Object>>>() {
                    });
            return parsed == null ? List.of() : parsed;
        } catch (Exception e) {
            log.warn("[resume-optimize] parse stored json failed: {}", e.getMessage());
            return List.of();
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
