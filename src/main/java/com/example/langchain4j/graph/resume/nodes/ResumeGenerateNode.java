package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import com.example.langchain4j.graph.resume.model.ResumeChange;
import com.example.langchain4j.graph.resume.model.ResumeOptimizeMode;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.LlmResult;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历生成节点：拼提示词 → LLM JSON 生成 → 解析改稿与变更清单。
 * <p>
 * 重试策略分三层，互不混淆：
 * <ol>
 *   <li>LlmGateway：网络抖动/超时/5xx，指数退避 + 熔断；</li>
 *   <li>本节点：输出被 maxOutputTokens 截断或不是合法 JSON，换更大预算 + 严格 JSON 提示重试一次；</li>
 *   <li>图上的 validate → generate 环：改稿不符合业务约束（丢章节/改事实）时带原因重写。</li>
 * </ol>
 * LLM 异常不向上抛，写 error 交由路由转兜底，保证图永远有输出。
 */
@Slf4j
@Component
public class ResumeGenerateNode implements NodeAction<ResumeOptimizeState> {

    private static final int MAX_SUMMARY_CHARS = 200;

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final AgentProperties properties;

    public ResumeGenerateNode(LlmGateway llmGateway,
                              PromptTemplateService promptTemplateService,
                              AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(ResumeOptimizeState state) {
        Map<String, Object> updates = new HashMap<>();
        updates.put(ResumeOptimizeState.ERROR, "");
        updates.put(ResumeOptimizeState.TRUNCATED, false);

        ResumeOptimizeMode mode = ResumeOptimizeMode.from(state.mode());
        if (mode == null) {
            updates.put(ResumeOptimizeState.ERROR, "不支持的优化模式：" + state.mode());
            return updates;
        }

        String systemPrompt = buildSystemPrompt(mode);
        int firstBudget = Math.max(1_024, properties.getResume().getMaxOutputTokens());
        int retryBudget = Math.max(firstBudget, properties.getResume().getRetryMaxOutputTokens());
        int maxChanges = Math.max(1, properties.getResume().getMaxChanges());
        int totalTokens = 0;
        String lastError = "";

        for (int attempt = 0; attempt < 2; attempt++) {
            int budget = attempt == 0 ? firstBudget : retryBudget;
            String userPrompt = buildUserPrompt(state, mode, attempt > 0);
            try {
                LlmResult result = call(state.model(), systemPrompt, userPrompt, budget);
                if (result.tokenUsage() != null) {
                    totalTokens += result.tokenUsage();
                }
                boolean truncated = result.truncated();
                Parsed parsed = truncated ? null : parse(result.text(), maxChanges);
                if (parsed != null) {
                    updates.put(ResumeOptimizeState.REVISED_MD, parsed.revised());
                    updates.put(ResumeOptimizeState.CHANGES, parsed.changes());
                    if (totalTokens > 0) {
                        updates.put(ResumeOptimizeState.TOKEN_USAGE, totalTokens);
                    }
                    log.info("[resume-generate] success, attempt={}, inputChars={}, outputChars={}, changes={}, tokens={}",
                            attempt + 1, state.resumeMd().length(), parsed.revised().length(),
                            parsed.changes().size(), totalTokens);
                    return updates;
                }
                lastError = truncated
                        ? "模型输出达到长度上限，改稿被截断。请精简简历，或更换支持更大输出长度的模型后重试。"
                        : "模型输出不是合法 JSON，简历优化失败";
                log.warn("[resume-generate] attempt {} unusable, truncated={}", attempt + 1, truncated);
            } catch (BizException e) {
                // 网关已重试/熔断，节点内不再重复调用；直接转兜底
                lastError = e.getMessage() == null ? "大模型服务暂时不可用" : e.getMessage();
                log.warn("[resume-generate] llm failed: {}", lastError);
                break;
            } catch (Exception e) {
                lastError = "简历生成失败：" + e.getMessage();
                log.warn("[resume-generate] unexpected error", e);
                break;
            }
        }

        updates.put(ResumeOptimizeState.ERROR,
                lastError.isBlank() ? "简历优化生成失败，请稍后重试" : lastError);
        updates.put(ResumeOptimizeState.TRUNCATED, lastError.contains("长度上限"));
        if (totalTokens > 0) {
            updates.put(ResumeOptimizeState.TOKEN_USAGE, totalTokens);
        }
        return updates;
    }

    private String buildSystemPrompt(ResumeOptimizeMode mode) {
        if (mode == ResumeOptimizeMode.FREE) {
            return promptTemplateService.resolve(
                    "RESUME_OPTIMIZE_FREE_SYSTEM", PromptTemplates.RESUME_OPTIMIZE_FREE_SYSTEM);
        }
        return promptTemplateService.resolve(
                "RESUME_OPTIMIZE_TARGETED_SYSTEM", PromptTemplates.RESUME_OPTIMIZE_TARGETED_SYSTEM);
    }

    private String buildUserPrompt(ResumeOptimizeState state, ResumeOptimizeMode mode, boolean strict) {
        String truncateNote = state.inputTruncated()
                ? "（原文已截断，后段未参与改写）\n\n" : "";
        String prompt;
        if (mode == ResumeOptimizeMode.FREE) {
            String sections = state.selectedSections().isEmpty()
                    ? "全部章节"
                    : String.join("、", state.selectedSections());
            prompt = promptTemplateService.resolveFormatted(
                    "RESUME_OPTIMIZE_FREE_USER", PromptTemplates.RESUME_OPTIMIZE_FREE_USER,
                    truncateNote, state.resumeMd(), sections, state.optimizationPrompt());
        } else {
            prompt = promptTemplateService.resolveFormatted(
                    "RESUME_OPTIMIZE_TARGETED_USER", PromptTemplates.RESUME_OPTIMIZE_TARGETED_USER,
                    truncateNote, state.resumeMd(), state.targetBlock());
        }

        if (state.retryCount() > 0) {
            String reason = state.validation().reason();
            if (reason != null && !reason.isBlank()) {
                String retryPrompt = promptTemplateService.resolveFormatted(
                        "RESUME_OPTIMIZE_RETRY_PROMPT", PromptTemplates.RESUME_OPTIMIZE_RETRY_PROMPT, reason);
                prompt = retryPrompt + "\n\n" + prompt;
            }
        }
        if (strict) {
            prompt = prompt + promptTemplateService.resolve(
                    "RESUME_OPTIMIZE_STRICT_JSON_NOTE", PromptTemplates.RESUME_OPTIMIZE_STRICT_JSON_NOTE);
        }
        return prompt;
    }

    /**
     * JSON 模式优先；供应商不支持 response_format 时降级为普通调用 + 调用方提取 JSON。
     * 普通调用也会走网关的重试/熔断，不会裸调 ChatModel。
     */
    private LlmResult call(String model, String systemPrompt, String userPrompt, int maxTokens) {
        try {
            return llmGateway.generateJson(model, systemPrompt, userPrompt, maxTokens);
        } catch (BizException e) {
            log.warn("[resume-generate] json mode failed, fallback to plain call: {}", e.getMessage());
            return llmGateway.generate(model, systemPrompt, userPrompt);
        }
    }

    private Parsed parse(String text, int maxChanges) {
        try {
            String cleaned = text == null ? "" : text.replaceAll("[\\p{Cntrl}]", " ");
            String json = JsonUtil.extractJsonObject(cleaned);
            if (json.isBlank()) {
                return null;
            }
            JsonNode root = JsonUtil.fromJson(json, JsonNode.class);
            if (root == null || !root.isObject()) {
                return null;
            }
            String revised = root.path("revisedMarkdown").asText("").trim();
            if (revised.isEmpty()) {
                return null;
            }
            List<Map<String, Object>> changes = new ArrayList<>();
            JsonNode array = root.path("changes");
            if (array.isArray()) {
                for (JsonNode item : array) {
                    if (changes.size() >= maxChanges) {
                        break;
                    }
                    Map<String, Object> change = new LinkedHashMap<>();
                    change.put("section", item.path("section").asText(""));
                    change.put("type", ResumeChange.normalizeType(item.path("type").asText("")));
                    change.put("summary", truncate(item.path("summary").asText(""), MAX_SUMMARY_CHARS));
                    change.put("reason", truncate(item.path("reason").asText(""), MAX_SUMMARY_CHARS));
                    changes.add(change);
                }
            }
            return new Parsed(revised, changes);
        } catch (Exception e) {
            log.warn("[resume-generate] json parse failed: {}", e.getMessage());
            return null;
        }
    }

    private String truncate(String text, int limit) {
        if (text == null) {
            return "";
        }
        String trimmed = text.trim();
        return trimmed.length() <= limit ? trimmed : trimmed.substring(0, limit);
    }

    private record Parsed(String revised, List<Map<String, Object>> changes) {
    }
}
