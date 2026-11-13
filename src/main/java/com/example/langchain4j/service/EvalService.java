package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.dto.EvalCaseRequest;
import com.example.langchain4j.dto.EvalReportResponse;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.LlmResult;
import com.example.langchain4j.llm.ModelRegistry;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.repository.entity.EvalCase;
import com.example.langchain4j.repository.entity.EvalResult;
import com.example.langchain4j.repository.mapper.EvalCaseMapper;
import com.example.langchain4j.repository.mapper.EvalResultMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

/**
 * 评测服务：离线回归集管理 + 召回率/忠实度评测 + 报告查询。
 * <p>
 * 指标定义：
 * <ul>
 *   <li>召回率：期望关键词在检索上下文中的命中比例（词面匹配，客观可复现，不依赖 LLM）；</li>
 *   <li>忠实度：LLM 判分 0~1，回答每处无资料支撑的编造陈述扣 0.2（与线上 validate 的 grounding 同目标，
 *       离线量化可对比不同检索/生成策略的回归差异）。</li>
 * </ul>
 * 评测基于“调用者可见知识范围”检索（公共库 + 调用者私有库，与线上检索同一链路）；
 * 单用例失败不中断整批（status=FAILED 落库），无关键词/判分失败的指标为空、不参与均值。
 */
@Slf4j
@Service
public class EvalService {

    private static final int EVAL_TOP_K = 3;
    private static final int CTX_MAX_LENGTH = 4000;

    private final EvalCaseMapper evalCaseMapper;
    private final EvalResultMapper evalResultMapper;
    private final VectorSearchService vectorSearchService;
    private final LlmGateway llmGateway;
    private final ModelRegistry modelRegistry;
    private final PromptTemplateService promptTemplateService;

    public EvalService(EvalCaseMapper evalCaseMapper,
                       EvalResultMapper evalResultMapper,
                       VectorSearchService vectorSearchService,
                       LlmGateway llmGateway,
                       ModelRegistry modelRegistry,
                       PromptTemplateService promptTemplateService) {
        this.evalCaseMapper = evalCaseMapper;
        this.evalResultMapper = evalResultMapper;
        this.vectorSearchService = vectorSearchService;
        this.llmGateway = llmGateway;
        this.modelRegistry = modelRegistry;
        this.promptTemplateService = promptTemplateService;
    }

    /** 新增评测用例（keywords 序列化为 JSON 数组字符串落库） */
    public long addCase(EvalCaseRequest request) {
        EvalCase evalCase = new EvalCase();
        evalCase.setQuestion(request.question().trim());
        evalCase.setKeywords(request.keywords() == null || request.keywords().isEmpty()
                ? null : JsonUtil.toJson(request.keywords()));
        evalCase.setExpectedAnswer(request.expectedAnswer());
        evalCase.setEnabled(true);
        evalCaseMapper.insert(evalCase);
        log.info("[eval] case added, id={}", evalCase.getId());
        return evalCase.getId();
    }

    /** 列出全部评测用例 */
    public List<EvalCase> listCases() {
        List<EvalCase> cases = evalCaseMapper.selectAll();
        return cases == null ? List.of() : cases;
    }

    /** 启用/停用用例（停用后不再参与评测） */
    public void setCaseEnabled(long id, boolean enabled) {
        if (evalCaseMapper.updateEnabled(id, enabled) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测用例不存在: " + id);
        }
    }

    /**
     * 执行全量评测：遍历启用用例，逐条「检索 → 召回打分 → 生成答案 → 忠实度判分」并落库。
     * 单用例异常独立捕获（FAILED 落库），整批结束后返回批次报告。
     */
    public EvalReportResponse runEval() {
        List<EvalCase> cases = evalCaseMapper.selectEnabled();
        if (cases == null || cases.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "无启用的评测用例，请先添加");
        }
        String runId = UUID.randomUUID().toString().replace("-", "");
        String model = modelRegistry.resolve(null);
        for (EvalCase evalCase : cases) {
            try {
                evaluateOne(runId, evalCase, model);
            } catch (Exception e) {
                log.error("[eval] case {} failed: {}", evalCase.getId(), e.getMessage());
                saveFailed(runId, evalCase, e);
            }
        }
        log.info("[eval] run finished, runId={}, cases={}", runId, cases.size());
        return buildReport(runId);
    }

    /** 最新批次报告（无任何评测数据时返回空报告） */
    public EvalReportResponse latestReport() {
        String runId = evalResultMapper.selectLatestRunId();
        if (runId == null) {
            return new EvalReportResponse(null, null, null, 0, 0, List.of());
        }
        return buildReport(runId);
    }

    /** 指定批次报告 */
    public EvalReportResponse report(String runId) {
        List<EvalResult> results = evalResultMapper.selectByRunId(runId);
        if (results == null || results.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测批次不存在: " + runId);
        }
        return assemble(runId, results);
    }

    // ---- 单用例评测 ---- //

    private void evaluateOne(String runId, EvalCase evalCase, String model) {
        // 步骤1：检索（与线上同一链路：命中子块父子装配父块整章；评测知识范围为调用者可见：公共库 + 本人库）
        String context = vectorSearchService.searchContext(evalCase.getQuestion(), EVAL_TOP_K);
        // 步骤2：召回打分（期望关键词在检索上下文中的命中比例；无关键词跳过）
        BigDecimal recall = recallScore(evalCase.getKeywords(), context);
        // 步骤3：基于检索上下文生成答案（与线上生成同一套“只用资料”约束）
        String answer = generateAnswer(model, context, evalCase.getQuestion());
        // 步骤4：忠实度判分（LLM 判分失败为空，不参与均值）
        BigDecimal faithfulness = faithfulnessScore(model, context, evalCase.getQuestion(), answer);
        saveSuccess(runId, evalCase, context, answer, recall, faithfulness);
    }

    /** 召回率：期望关键词在检索上下文中的命中比例（上下文为空 → 0） */
    private BigDecimal recallScore(String keywordsJson, String context) {
        if (keywordsJson == null || keywordsJson.isBlank()) {
            return null;
        }
        List<String> keywords;
        try {
            keywords = JsonUtil.fromJson(keywordsJson, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("[eval] keywords json parse failed, skip recall: {}", e.getMessage());
            return null;
        }
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }
        String normalized = context == null ? "" : context;
        long hit = keywords.stream().filter(k -> k != null && !k.isBlank() && normalized.contains(k)).count();
        return BigDecimal.valueOf(hit)
                .divide(BigDecimal.valueOf(keywords.size()), 4, RoundingMode.HALF_UP);
    }

    private String generateAnswer(String model, String context, String question) {
        String userPrompt = "用户问题：\n" + question
                + "\n\n参考资料：\n" + (context == null || context.isBlank() ? "（无）" : context);
        // 提示词走配置化解析：DB 有覆盖用 DB 版，否则兑底代码默认值
        LlmResult result = llmGateway.generate(model,
                promptTemplateService.resolve("EVAL_ANSWER_SYSTEM", PromptTemplates.EVAL_ANSWER_SYSTEM),
                userPrompt);
        return result.text();
    }

    /** 忠实度：LLM 判分 0~1；判分不可用（JSON 解析失败/截断）返回 null */
    private BigDecimal faithfulnessScore(String model, String context, String question, String answer) {
        String userPrompt = "用户问题：\n" + question
                + "\n\n参考资料：\n" + (context == null || context.isBlank() ? "（无）" : context)
                + "\n\n候选回答：\n" + answer;
        try {
            // 提示词走配置化解析：DB 有覆盖用 DB 版，否则兑底代码默认值
            JsonNode node = llmGateway.generateJsonNode(model,
                    promptTemplateService.resolve("EVAL_FAITHFULNESS_SYSTEM", PromptTemplates.EVAL_FAITHFULNESS_SYSTEM),
                    userPrompt, "eval-faithfulness");
            if (node == null || !node.hasNonNull("score")) {
                return null;
            }
            double score = node.get("score").asDouble();
            return BigDecimal.valueOf(Math.max(0.0, Math.min(1.0, score)))
                    .setScale(4, RoundingMode.HALF_UP);
        } catch (Exception e) {
            log.warn("[eval] faithfulness judge failed, skip score: {}", e.getMessage());
            return null;
        }
    }

    // ---- 落库 ---- //

    private void saveSuccess(String runId, EvalCase evalCase, String context, String answer,
                             BigDecimal recall, BigDecimal faithfulness) {
        EvalResult result = baseResult(runId, evalCase);
        result.setStatus(EvalResult.STATUS_SUCCESS);
        result.setRetrievedCtx(truncate(context));
        result.setAnswer(answer);
        result.setRecallScore(recall);
        result.setFaithfulness(faithfulness);
        evalResultMapper.insert(result);
    }

    private void saveFailed(String runId, EvalCase evalCase, Exception e) {
        EvalResult result = baseResult(runId, evalCase);
        result.setStatus(EvalResult.STATUS_FAILED);
        result.setErrorMsg(truncate(String.valueOf(e.getMessage())));
        evalResultMapper.insert(result);
    }

    private EvalResult baseResult(String runId, EvalCase evalCase) {
        EvalResult result = new EvalResult();
        result.setRunId(runId);
        result.setCaseId(evalCase.getId());
        result.setQuestion(evalCase.getQuestion());
        return result;
    }

    // ---- 报告组装 ---- //

    private EvalReportResponse buildReport(String runId) {
        List<EvalResult> results = evalResultMapper.selectByRunId(runId);
        if (results == null || results.isEmpty()) {
            throw new BizException(ErrorCode.NOT_FOUND, "评测批次不存在: " + runId);
        }
        return assemble(runId, results);
    }

    private EvalReportResponse assemble(String runId, List<EvalResult> results) {
        BigDecimal recallSum = BigDecimal.ZERO;
        BigDecimal faithfulnessSum = BigDecimal.ZERO;
        int recallCount = 0;
        int faithfulnessCount = 0;
        long failed = 0;
        for (EvalResult r : results) {
            if (EvalResult.STATUS_FAILED.equals(r.getStatus())) {
                failed++;
            }
            if (r.getRecallScore() != null) {
                recallSum = recallSum.add(r.getRecallScore());
                recallCount++;
            }
            if (r.getFaithfulness() != null) {
                faithfulnessSum = faithfulnessSum.add(r.getFaithfulness());
                faithfulnessCount++;
            }
        }
        List<EvalReportResponse.Item> items = results.stream()
                .map(r -> new EvalReportResponse.Item(
                        r.getCaseId(), r.getQuestion(), r.getRecallScore(), r.getFaithfulness(),
                        r.getStatus(), r.getErrorMsg(), r.getRetrievedCtx(), r.getAnswer(), r.getCreatedAt()))
                .toList();
        return new EvalReportResponse(
                runId,
                recallCount == 0 ? null : avg(recallSum, recallCount),
                faithfulnessCount == 0 ? null : avg(faithfulnessSum, faithfulnessCount),
                results.size(), failed, items);
    }

    private BigDecimal avg(BigDecimal sum, int count) {
        return sum.divide(BigDecimal.valueOf(count), 4, RoundingMode.HALF_UP);
    }

    /** 长文本截断（报告明细防超长刷屏；截断不影响指标计算，指标在截断前完成） */
    private String truncate(String text) {
        if (text == null) {
            return null;
        }
        return text.length() <= CTX_MAX_LENGTH ? text : text.substring(0, CTX_MAX_LENGTH) + "...[截断]";
    }
}
