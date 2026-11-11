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
import com.example.langchain4j.repository.entity.ResumeRevision;
import com.example.langchain4j.repository.mapper.ResumeRevisionMapper;
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
 * 简历优化服务：限流 → 校验 → 建草稿 → 图执行（带整体超时）→ 落结果/错误。
 * <p>
 * 图本身是无状态的，并发与重复提交控制在这里：
 * <ul>
 *   <li>{@link RateLimiter} 按用户限流；</li>
 *   <li>{@link SessionLock} 按用户串行，避免同一用户并发跑多份简历打爆 LLM 配额；</li>
 *   <li>图执行放 agentExecutor 并整体超时，超时取消后台任务并落 ERROR；</li>
 *   <li>失败只返回明确错误，不返回半份简历。</li>
 * </ul>
 */
@Slf4j
@Service
public class ResumeOptimizeService {

    private static final int MAX_LIST_LIMIT = 100;
    private static final int MAX_ERROR_CHARS = 1_000;

    private final CompiledGraph<ResumeOptimizeState> resumeOptimizeGraph;
    private final RateLimiter rateLimiter;
    private final SessionLock sessionLock;
    private final ModelRegistry modelRegistry;
    private final AgentProperties properties;
    private final AgentMetrics metrics;
    private final AuditService auditService;
    private final ResumeRevisionMapper resumeRevisionMapper;
    private final ThreadPoolTaskExecutor agentExecutor;

    public ResumeOptimizeService(@Qualifier("resumeOptimizeGraph") CompiledGraph<ResumeOptimizeState> resumeOptimizeGraph,
                                 RateLimiter rateLimiter,
                                 SessionLock sessionLock,
                                 ModelRegistry modelRegistry,
                                 AgentProperties properties,
                                 AgentMetrics metrics,
                                 AuditService auditService,
                                 ResumeRevisionMapper resumeRevisionMapper,
                                 @Qualifier("agentExecutor") ThreadPoolTaskExecutor agentExecutor) {
        this.resumeOptimizeGraph = resumeOptimizeGraph;
        this.rateLimiter = rateLimiter;
        this.sessionLock = sessionLock;
        this.modelRegistry = modelRegistry;
        this.properties = properties;
        this.metrics = metrics;
        this.auditService = auditService;
        this.resumeRevisionMapper = resumeRevisionMapper;
        this.agentExecutor = agentExecutor;
    }

    /**
     * 执行一次简历优化。
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

        String revisionId = "rv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String title = resolveTitle(request.getResumeTitle());
        String targetJson = buildTargetJson(mode, jobs, request.getSelectedSections(), prompt);

        ResumeRevision draft = new ResumeRevision();
        draft.setRevisionId(revisionId);
        draft.setUserId(userId);
        draft.setResumeTitle(title);
        draft.setMode(mode.name());
        draft.setTargetJson(targetJson);
        draft.setOriginalMd(request.getResumeText());
        draft.setStatus(ResumeRevision.STATUS_RUNNING);
        draft.setModel(model);
        resumeRevisionMapper.insert(draft);

        long start = System.currentTimeMillis();
        String auditInput = auditInput(mode, jobs.size(),
                request.getResumeText().length(), request.getSelectedSections());
        // lambda 捕获需要 effectively final：jobs/prompt 在模式分支里重新赋值过
        List<ResumeTargetJob> finalJobs = jobs;
        String finalPrompt = prompt;
        try {
            ResumeOptimizeState state = sessionLock.executeWithLock("resume:" + userId,
                    () -> invokeGraph(revisionId, request, mode, finalJobs, finalPrompt, model));
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

    private ResumeOptimizeState invokeGraph(String revisionId, ResumeOptimizeRequest request,
                                            ResumeOptimizeMode mode, List<ResumeTargetJob> jobs,
                                            String prompt, String model) {
        Map<String, Object> inputs = new HashMap<>();
        inputs.put(ResumeOptimizeState.RESUME_RAW, request.getResumeText());
        inputs.put(ResumeOptimizeState.MODE, mode.name());
        inputs.put(ResumeOptimizeState.TARGET_JOBS,
                jobs.stream().map(ResumeTargetJob::toMap).toList());
        inputs.put(ResumeOptimizeState.SELECTED_SECTIONS,
                request.getSelectedSections() == null ? List.of() : request.getSelectedSections());
        inputs.put(ResumeOptimizeState.OPTIMIZATION_PROMPT, prompt);
        inputs.put(ResumeOptimizeState.MODEL, model);

        RunnableConfig config = RunnableConfig.builder().threadId(revisionId).build();
        int timeoutSeconds = Math.max(10, properties.getResume().getTimeoutSeconds());
        Future<ResumeOptimizeState> future;
        try {
            future = agentExecutor.getThreadPoolExecutor().submit(
                    () -> resumeOptimizeGraph.invoke(inputs, config).orElse(null));
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
