package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.guard.OutputGuardService;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.LlmResult;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务回答节点：把工具轨迹与证据合成最终答复，并给出可解释置信度。
 * <p>
 * 证据清单由系统统一追加（不依赖模型自觉引用），保证每条回答都有可追溯来源；
 * 置信度采用确定性启发式，便于离线复现与验证，不引入额外 LLM 判分成本。
 */
@Slf4j
@Component
public class TaskAnswerNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;
    private final OutputGuardService outputGuardService;
    private final PromptTemplateService promptTemplateService;
    private final AgentProperties properties;

    /** 注入 LLM、输出安全审核与提示词服务。 */
    public TaskAnswerNode(LlmGateway llmGateway,
                          OutputGuardService outputGuardService,
                          PromptTemplateService promptTemplateService,
                          AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.outputGuardService = outputGuardService;
        this.promptTemplateService = promptTemplateService;
        this.properties = properties;
    }

    /** 基于工具轨迹和证据生成回答，计算置信度并统一追加参考依据。 */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        List<Map<String, Object>> evidence = state.taskEvidence();
        List<Map<String, Object>> trace = state.taskTrace();
        String answer;
        Integer tokenUsage = null;
        try {
            // 步骤1：优先让模型基于证据组织自然语言回答
            LlmResult result = llmGateway.generate(LlmRequest.of(state.model(),
                    promptTemplateService.resolve("TASK_ANSWER_SYSTEM", PromptTemplates.TASK_ANSWER_SYSTEM),
                    buildUserPrompt(state, evidence, trace)).scene("task-answer"));
            answer = result.text();
            tokenUsage = result.tokenUsage();
        } catch (BizException e) {
            // 步骤2：模型不可用时降级为确定性摘要，保证任务轮仍有可读结果
            log.warn("[task-answer] llm failed, use deterministic summary: {}", e.getMessage());
            answer = deterministicAnswer(trace, evidence);
        } catch (Exception e) {
            log.error("[task-answer] unexpected error, use deterministic summary", e);
            answer = deterministicAnswer(trace, evidence);
        }
        // 步骤3：输出侧安全审核（与主生成同一套敏感词门禁）
        if (outputGuardService.blocked(answer)) {
            answer = OutputGuardService.BLOCKED_ANSWER;
        } else {
            // 步骤4：系统统一追加参考依据，保证引用覆盖率可统计；被安全拦截时不追加，避免泄露来源
            answer = appendCitations(answer, evidence);
        }
        // 步骤5：确定性置信度评分
        long successCalls = trace.stream().filter(item -> Boolean.TRUE.equals(item.get("success"))).count();
        int failures = trace.size() - (int) successCalls;
        double confidence = confidence(evidence.size(), (int) successCalls, failures);
        String reason = confidenceReason(evidence.size(), (int) successCalls, failures);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.RESULT, answer);
        updates.put(WorkflowState.TASK_CONFIDENCE, confidence);
        updates.put(WorkflowState.TASK_CONFIDENCE_REASON, reason);
        updates.put(WorkflowState.ERROR, "");
        if (tokenUsage != null) {
            updates.put(WorkflowState.TOKEN_USAGE, tokenUsage);
        }
        log.info("[task-answer] evidence={}, calls={}, confidence={}, resultLength={}, tokenUsage={}",
                evidence.size(), trace.size(), confidence,
                answer == null ? 0 : answer.length(), tokenUsage);
        return updates;
    }

    /** 拼装用户问题、工具轨迹和证据清单，供回答模型参考。 */
    private String buildUserPrompt(WorkflowState state,
                                   List<Map<String, Object>> evidence,
                                   List<Map<String, Object>> trace) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户问题：\n").append(state.effectiveQuery()).append("\n\n");
        builder.append("工具执行轨迹：\n");
        if (trace.isEmpty()) {
            builder.append("（无）\n");
        } else {
            int index = 1;
            for (Map<String, Object> item : trace) {
                builder.append(index++).append(". 工具=").append(item.get("tool"))
                        .append("，成功=").append(item.get("success"))
                        .append("，结果=").append(item.get("summary"));
                Object errorCode = item.get("errorCode");
                if (errorCode != null && !String.valueOf(errorCode).isBlank()) {
                    builder.append("，错误码=").append(errorCode);
                }
                builder.append('\n');
            }
        }
        builder.append("\n工具证据：\n");
        if (evidence.isEmpty()) {
            builder.append("（无）\n");
        } else {
            int index = 1;
            for (Map<String, Object> item : evidence) {
                if (index > maxEvidence()) {
                    break;
                }
                builder.append('[').append(index++).append("] ")
                        .append(item.get("title")).append("：")
                        .append(Texts.truncate(String.valueOf(item.get("snippet")), maxSnippet()))
                        .append('\n');
            }
        }
        builder.append("\n请只基于以上证据回答，不要编造。");
        return builder.toString();
    }

    /** 模型不可用时的确定性答复：直接汇总工具结果，不编造 */
    private String deterministicAnswer(List<Map<String, Object>> trace,
                                       List<Map<String, Object>> evidence) {
        if (!evidence.isEmpty()) {
            return "已查询到相关业务数据，关键结果如下：\n\n"
                    + Texts.truncate(String.valueOf(evidence.get(0).get("snippet")), maxSnippet());
        }
        if (!trace.isEmpty()) {
            return "本次查询未返回有效数据。\n\n"
                    + Texts.truncate(String.valueOf(trace.get(0).get("summary")), maxSnippet());
        }
        return "未查询到相关业务数据，请确认查询条件或稍后重试。";
    }

    /** 统一追加参考依据；evidence 已按来源去重前的顺序截断 */
    private String appendCitations(String answer, List<Map<String, Object>> evidence) {
        if (evidence.isEmpty() || answer == null || answer.isBlank()) {
            return answer;
        }
        if (answer.contains("**参考依据**")) {
            return answer;
        }
        StringBuilder builder = new StringBuilder(answer.trim());
        builder.append("\n\n**参考依据**\n");
        int index = 1;
        for (Map<String, Object> item : evidence) {
            if (index > maxEvidence()) {
                break;
            }
            builder.append(index++).append(". ")
                    .append(item.get("title"))
                    .append("（来源：").append(item.get("sourceId")).append("）\n");
        }
        return builder.toString().trim();
    }

    /** 按证据数量与工具失败次数计算确定性置信度。 */
    private double confidence(int evidenceCount, int successCalls, int failures) {
        if (evidenceCount == 0) {
            return successCalls > 0 ? 0.35 : 0.2;
        }
        double score = 0.5 + 0.1 * Math.min(3, evidenceCount);
        if (failures == 0) {
            score += 0.15;
        } else {
            score -= 0.1 * failures;
        }
        return Math.max(0.3, Math.min(0.95, Math.round(score * 100.0) / 100.0));
    }

    /** 生成可解释的置信度说明。 */
    private String confidenceReason(int evidenceCount, int successCalls, int failures) {
        if (evidenceCount == 0) {
            return "未获得工具证据（成功调用 " + successCalls + " 次，失败 " + failures + " 次）";
        }
        return "基于 " + evidenceCount + " 条工具证据，工具调用成功 " + successCalls + " 次、失败 " + failures + " 次";
    }

    private int maxEvidence() {
        return properties.getLimits().getTaskAnswerMaxEvidence();
    }

    private int maxSnippet() {
        return properties.getLimits().getTaskAnswerMaxSnippet();
    }
}
