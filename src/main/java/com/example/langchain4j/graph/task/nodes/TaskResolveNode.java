package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.model.TaskOperation;
import com.example.langchain4j.graph.task.model.TaskReadTarget;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.model.LlmRequest;
import com.example.langchain4j.prompt.PromptTemplates;
import com.example.langchain4j.prompt.PromptTemplateService;
import com.example.langchain4j.tool.api.constant.ToolNames;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** 注入模型网关、提示词服务和 ReAct 配置；节点本身无本地状态，可安全复用。 */
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
            // 步骤1：只让模型输出结构化解析结果，本节点不执行工具，也不写业务数据。
            JsonNode node = llmGateway.generateJson(
                    LlmRequest.of(state.model(), systemPrompt, userPrompt).scene("task-resolve"));
            if (node == null) {
                return error("任务解析失败：模型输出为空或非 JSON");
            }

            // 步骤2：操作类型必须明确；无法识别时直接兜底，避免猜测成固定查询或写操作。
            TaskOperation operation = TaskOperation.parse(node.path("operation").asText(""));
            if (operation == null) {
                return error("无法判断是查询还是修改，请明确说明要做什么");
            }
            if (operation == TaskOperation.REACT && !properties.getReact().isEnabled()) {
                // REACT 关闭时采用 fail-closed：不静默降级成固定查询，防止用户意图被改写。
                log.warn("[task-resolve] react disabled, queryLength={}",
                        state.effectiveQuery().length());
                return error("当前未启用多步诊断，请明确说明要查询报名、额度、交易还是执行修改");
            }

            // 步骤3：REACT 的查询目标由规划节点决定，这里不抽取固定参数；
            // 只有 READ/WRITE 才解析参数和缺失项，避免污染 ReAct 白名单工具调用。
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
            // 多步写计划：只接受白名单写工具；模型未给 steps 时用 args 兜底为单步
            List<Map<String, Object>> plan = operation == TaskOperation.WRITE
                    ? parsePlan(node.get("steps"), args) : List.of();
            if (operation == TaskOperation.WRITE && plan.isEmpty()) {
                addMissing(missing, "要修改的记录");
            }
            // 步骤4：固定读取才校验目标表和关联查询前置条件；一次只允许一段关联，控制查询复杂度。
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
            // 步骤5：统一重置来源和待确认动作，避免 checkpoint 恢复时复用上一轮的业务上下文。
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.TASK_OPERATION, operation.name());
            updates.put(WorkflowState.TASK_RESOLVE_REASON, node.path("reason").asText(""));
            updates.put(WorkflowState.TASK_ARGS, args);
            updates.put(WorkflowState.TASK_PLAN, plan);
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

            // 步骤6：缺参不进入执行节点，直接生成追问并结束本轮，减少无效查询和误写风险。
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

            // 步骤7：参数完整时写确认标记：只有 WRITE 需要人工确认，READ/REACT 都是只读动作。
            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, operation == TaskOperation.WRITE);
            updates.put(WorkflowState.ERROR, "");
            log.info("[task-resolve] done, operation={}, readTarget={}, withTransactions={}, withQuotas={}, "
                            + "argKeys={}, planSteps={}, reason={}, costMs={}",
                    operation, readTarget, updates.get(WorkflowState.TASK_INCLUDE_TRANSACTIONS),
                    updates.get(WorkflowState.TASK_INCLUDE_QUOTAS),
                    args.keySet(), plan.size(), node.path("reason").asText(""),
                    System.currentTimeMillis() - start);
            return updates;
        } catch (BizException e) {
            // 步骤8：模型调用失败统一转成图级 error，由 Router 进入 fallback，不向上抛裸异常。
            log.warn("[task-resolve] llm failed: {}", e.getMessage());
            return error("任务解析失败: " + e.getMessage());
        } catch (Exception e) {
            log.error("[task-resolve] unexpected error", e);
            return error("任务解析失败: " + e.getMessage());
        }
    }

    /**
     * 解析模型返回的固定查询或写操作参数。
     * 只接受 JSON 对象并转换成字符串键 Map；非对象、空值和反序列化异常都由调用方兜底为空参数。
     */
    private Map<String, Object> parseArgs(JsonNode node) {
        if (node == null || !node.isObject()) {
            return Map.of();
        }
        Map<String, Object> args = JsonUtil.mapper().convertValue(
                node, new TypeReference<Map<String, Object>>() {
                });
        return args == null ? Map.of() : args;
    }

    /**
     * 解析缺失参数列表。
     * 只接受字符串数组并过滤空白值，避免空标签生成无意义的追问文案。
     */
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
     * 解析多步写计划。
     *
     * 只接受白名单写工具（当前只有 complete_demo_record），按参数指纹去重，
     * 最多 maxWriteSteps 步；模型没有输出 steps 时用顶层 args 兜底成单步，
     * 保证旧提示词/旧模型输出仍然可用。
     *
     * @param node         模型输出的 steps 数组（可能为空）
     * @param fallbackArgs 顶层 args（单步兜底）
     * @return 按执行顺序排列的步骤列表，空列表表示没有可执行写操作
     */
    private List<Map<String, Object>> parsePlan(JsonNode node, Map<String, Object> fallbackArgs) {
        int maxSteps = Math.max(1, properties.getTool().getMaxWriteSteps());
        List<Map<String, Object>> plan = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        if (node != null && node.isArray()) {
            for (JsonNode step : node) {
                if (plan.size() >= maxSteps) {
                    break;
                }
                String tool = step.path("tool").asText("").trim();
                if (!ToolNames.COMPLETE_DEMO_RECORD.equals(tool)) {
                    continue;
                }
                Map<String, Object> stepArgs = parseArgs(step.get("args"));
                if (stepArgs.isEmpty()) {
                    continue;
                }
                // 同一参数重复出现只保留一次，避免模型把一句话拆出重复写操作
                String fingerprint = tool + "|" + JsonUtil.toJson(stepArgs);
                if (!seen.add(fingerprint)) {
                    continue;
                }
                plan.add(stepItem(plan.size() + 1, tool, stepArgs,
                        step.path("summary").asText("")));
            }
        }
        if (plan.isEmpty() && fallbackArgs != null && !fallbackArgs.isEmpty()) {
            plan.add(stepItem(1, ToolNames.COMPLETE_DEMO_RECORD, fallbackArgs, ""));
        }
        return plan;
    }

    /** 构造统一的步骤结构：stepNo 从 1 开始，供确认卡片、执行器和台账共用。 */
    private Map<String, Object> stepItem(int stepNo, String tool, Map<String, Object> args,
                                         String summary) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("stepNo", stepNo);
        item.put("tool", tool);
        item.put("args", args);
        item.put("summary", summary == null ? "" : summary.trim());
        return item;
    }

    /**
     * 关联查询必须能唯一定位来源记录：报名关联额度需要报名单号；
     * 报名关联交易需要报名单号；额度关联交易需要活动编号和月份。
     *
     * 该方法只补 missing 提示，不修改模型给出的参数，也不猜测用户未提供的业务值。
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

    /** 追加缺失参数提示；同一类提示只保留一次，避免追问文案重复。 */
    private void addMissing(List<String> missing, String slot) {
        if (missing.stream().noneMatch(item -> item.contains(slot))) {
            missing.add(slot);
        }
    }

    /** 读取参数文本；缺失或空值转空串，供前置条件判断使用。 */
    private String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 构造图级失败增量；只写 ERROR，由 Router 统一决定进入 fallback。 */
    private Map<String, Object> error(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        return updates;
    }
}
