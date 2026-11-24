package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.TaskOperation;
import com.example.langchain4j.graph.task.TaskReadTarget;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 任务解析节点：一次模型调用完成“判断固定读写 / 受控只读诊断、选择只读目标表、抽取参数”。
 *
 * 本节点只产出业务动作候选，不执行工具，也不校验真实业务权限、状态或工具策略。
 * 真实执行约束由固定执行节点、工具执行器和领域服务负责。
 */
@Slf4j
@Component
public class TaskResolveNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final AgentProperties properties;

    public TaskResolveNode(LlmGateway llmGateway, PromptTemplateService promptTemplateService,
                           AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.properties = properties;
    }

    /**
     * 解析用户请求并写入操作类型、参数和缺失项。
     * 缺少必要参数时直接生成追问文案，不进入读写执行节点。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        long start = System.currentTimeMillis();
        String systemPrompt = promptTemplateService.resolve(
                "TASK_RESOLVE_SYSTEM", PromptTemplates.TASK_RESOLVE_SYSTEM);
        String userPrompt = "用户请求：\n" + state.effectiveQuery();
        log.info("[task-resolve] start, queryLength={}, model={}", state.effectiveQuery().length(),
                state.model());

        try {
            JsonNode node = llmGateway.generateJson(
                    LlmRequest.of(state.model(), systemPrompt, userPrompt).scene("task-resolve"));
            if (node == null) {
                return error("任务解析失败：模型输出为空或非 JSON");
            }

            TaskOperation operation = TaskOperation.parse(node.path("operation").asText(""));
            if (operation == null) {
                return error("无法判断是查询还是修改，请明确说明要做什么");
            }
            if (operation == TaskOperation.REACT && !properties.getReact().isEnabled()) {
                log.warn("[task-resolve] react disabled, queryLength={}",
                        state.effectiveQuery().length());
                return error("当前未启用多步诊断，请明确说明要查询报名、额度、交易还是执行修改");
            }

            Map<String, Object> args = operation == TaskOperation.REACT
                    ? Map.of() : parseArgs(node.get("args"));
            List<String> missing = operation == TaskOperation.REACT
                    ? List.of() : parseMissing(node.get("missing"));
            TaskReadTarget readTarget = operation == TaskOperation.READ
                    ? TaskReadTarget.parse(node.path("readTarget").asText("")) : null;
            boolean includeTransactions = operation == TaskOperation.READ
                    && node.path("withTransactions").asBoolean(false);
            boolean includeQuotas = operation == TaskOperation.READ
                    && node.path("withQuotas").asBoolean(false);
            if (operation == TaskOperation.READ) {
                if (readTarget == null) {
                    return error("无法判断要查询报名、额度还是交易，请明确说明查询对象");
                }
                if (includeQuotas && includeTransactions) {
                    return error("一次只支持一段关联查询，请分别查询关联额度或关联交易");
                }
                if (includeQuotas && readTarget != TaskReadTarget.ENROLLMENT) {
                    return error("关联额度查询必须先以报名单号查询报名记录");
                }
                validateFollowUpPrerequisite(readTarget, includeTransactions, includeQuotas,
                        args, missing);
            }
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.TASK_OPERATION, operation.name());
            updates.put(WorkflowState.TASK_RESOLVE_REASON, node.path("reason").asText(""));
            updates.put(WorkflowState.TASK_ARGS, args);
            updates.put(WorkflowState.TASK_READ_TARGET, readTarget == null ? "" : readTarget.name());
            updates.put(WorkflowState.TASK_INCLUDE_TRANSACTIONS,
                    readTarget != null && readTarget != TaskReadTarget.TRANSACTION
                            && includeTransactions);
            updates.put(WorkflowState.TASK_INCLUDE_QUOTAS,
                    readTarget == TaskReadTarget.ENROLLMENT && includeQuotas);
            updates.put(WorkflowState.TASK_SOURCE_TYPE, "");
            updates.put(WorkflowState.TASK_SOURCE_ID, "");
            updates.put(WorkflowState.TASK_MISSING, missing);
            updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());

            if (!missing.isEmpty()) {
                String labels = String.join("、", missing);
                String answer = promptTemplateService.resolveFormatted(
                        "TASK_CLARIFY_ANSWER", PromptTemplates.TASK_CLARIFY_ANSWER, labels);
                updates.put(WorkflowState.TASK_CLARIFY, true);
                updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
                updates.put(WorkflowState.TASK_CONFIDENCE, 1.0);
                updates.put(WorkflowState.TASK_CONFIDENCE_REASON, "缺少必要参数，已生成追问");
                updates.put(WorkflowState.RESULT, answer);
                updates.put(WorkflowState.ERROR, "");
                log.info("[task-resolve] clarification required, operation={}, missing={}, costMs={}",
                        operation, missing, System.currentTimeMillis() - start);
                return updates;
            }

            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, operation == TaskOperation.WRITE);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-resolve] done, operation={}, readTarget={}, withTransactions={}, withQuotas={}, "
                            + "argKeys={}, reason={}, costMs={}",
                    operation, readTarget, updates.get(WorkflowState.TASK_INCLUDE_TRANSACTIONS),
                    updates.get(WorkflowState.TASK_INCLUDE_QUOTAS),
                    args.keySet(), node.path("reason").asText(""),
                    System.currentTimeMillis() - start);
            return updates;
        } catch (BizException e) {
            log.warn("[task-resolve] llm failed: {}", e.getMessage());
            return error("任务解析失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[task-resolve] unexpected error", e);
            return error("任务解析失败: " + e.getMessage());
        }
    }

    /** 参数只接受 JSON 对象，统一转换成字符串键 Map。 */
    private Map<String, Object> parseArgs(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> args = JsonUtil.mapper().convertValue(
                node, new TypeReference<Map<String, Object>>() {
                });
        return args == null ? Map.of() : args;
    }

    /** 缺失项只接受字符串数组，过滤空白值。 */
    private List<String> parseMissing(JsonNode node) {
        List<String> missing = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    missing.add(value);
                }
            });
        }
        return missing;
    }

    /**
     * 关联查询必须能唯一定位来源记录：报名关联额度需要报名单号；
     * 报名关联交易需要报名单号；额度关联交易需要活动编号和月份。
     */
    private void validateFollowUpPrerequisite(TaskReadTarget readTarget,
                                              boolean includeTransactions,
                                              boolean includeQuotas,
                                              Map<String, Object> args,
                                              List<String> missing) {
        if ((!includeTransactions && !includeQuotas) || readTarget == TaskReadTarget.TRANSACTION) {
            return;
        }
        if (includeQuotas) {
            if (text(args, "enrollmentNo").isBlank()) {
                addMissing(missing, "报名单号");
            }
            return;
        }
        if (readTarget == TaskReadTarget.ENROLLMENT && text(args, "enrollmentNo").isBlank()) {
            addMissing(missing, "报名单号");
        }
        if (readTarget == TaskReadTarget.QUOTA
                && (text(args, "activityNo").isBlank() || text(args, "month").isBlank())) {
            addMissing(missing, "活动编号和额度月份");
        }
    }

    private void addMissing(List<String> missing, String slot) {
        if (missing.stream().noneMatch(item -> item.contains(slot))) {
            missing.add(slot);
        }
    }

    private String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> error(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        return updates;
    }
}
