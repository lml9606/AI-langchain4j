package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.dto.request.LlmRequest;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.ReactToolCatalog;
import com.example.langchain4j.graph.task.TaskOperation;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.service.PromptTemplateService;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ReAct 规划节点：让模型在受控工具目录中选择下一步，或宣布结束。
 *
 * 模型只产出结构化动作，不执行工具；工具白名单、参数形状、步数和停止条件都在这里
 * 先做一次防御校验。真正的权限、Schema、限流和超时仍由 ToolExecutor 在执行节点复核。
 */
@Slf4j
@Component
public class ReactPlanNode implements NodeAction<WorkflowState> {

    private static final int MAX_REASON_LENGTH = 160;
    private static final int PLAN_MAX_OUTPUT_TOKENS = 512;

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final ReactToolCatalog toolCatalog;
    private final AgentProperties properties;

    public ReactPlanNode(LlmGateway llmGateway,
                         PromptTemplateService promptTemplateService,
                         ReactToolCatalog toolCatalog,
                         AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.toolCatalog = toolCatalog;
        this.properties = properties;
    }

    /**
     * 生成下一步动作：call_tool 进入工具执行节点，final 或任何停止条件进入回答节点。
     * 规划输出不可解析时按“已有证据足够”处理，避免把一次格式错误升级为整轮失败。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        if (TaskOperation.parse(state.taskOperation()) != TaskOperation.REACT) {
            return fail("ReAct 规划节点收到非 REACT 操作，已拒绝执行");
        }
        if (!properties.getReact().isEnabled()) {
            return fail("ReAct 只读诊断当前未启用");
        }
        if (state.reactDone()) {
            return stop(state.reactStopReason());
        }

        int maxSteps = Math.max(1, properties.getReact().getMaxSteps());
        if (state.reactStep() >= maxSteps) {
            log.info("[react-plan] max steps reached, step={}, max={}", state.reactStep(), maxSteps);
            return stop("已达到最大工具步数 " + maxSteps + "，基于已有证据回答");
        }

        long start = System.currentTimeMillis();
        List<ToolDefinition> tools = toolCatalog.definitions();
        if (tools.isEmpty()) {
            return fail("ReAct 没有可用的只读工具，请检查工具注册与启用状态");
        }
        try {
            String systemPrompt = promptTemplateService.resolve(
                    "REACT_PLAN_SYSTEM", PromptTemplates.REACT_PLAN_SYSTEM);
            JsonNode node = llmGateway.generateJson(
                    LlmRequest.of(state.model(), systemPrompt, buildUserPrompt(state, tools, maxSteps))
                            .maxOutputTokens(PLAN_MAX_OUTPUT_TOKENS)
                            .scene("react-plan"));
            if (node == null) {
                return stop("规划输出为空或不是合法 JSON，基于已有证据回答");
            }

            String action = node.path("action").asText("").trim().toLowerCase();
            String reason = Texts.truncate(node.path("reason").asText("").trim(), MAX_REASON_LENGTH);
            if ("final".equals(action)) {
                log.info("[react-plan] final, step={}, reason={}, costMs={}",
                        state.reactStep(), reason, System.currentTimeMillis() - start);
                return stop(reason.isBlank() ? "模型判断已有证据足够" : reason);
            }
            if (!"call_tool".equals(action)) {
                return stop("规划动作无效，基于已有证据回答");
            }

            String tool = node.path("tool").asText("").trim();
            if (!toolCatalog.allows(tool)) {
                log.warn("[react-plan] tool not allowed, tool={}, step={}", tool, state.reactStep());
                return stop("模型选择了未授权的工具，已停止继续调用");
            }
            JsonNode argsNode = node.get("args");
            if (argsNode != null && !argsNode.isNull() && !argsNode.isObject()) {
                return stop("工具参数不是 JSON 对象，基于已有证据回答");
            }
            Map<String, Object> args = parseArgs(argsNode);

            Map<String, Object> planned = new LinkedHashMap<>();
            planned.put("action", "call_tool");
            planned.put("step", state.reactStep() + 1);
            planned.put("tool", tool);
            planned.put("args", args);
            planned.put("reason", reason);

            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.REACT_ACTION, planned);
            updates.put(WorkflowState.REACT_DONE, false);
            updates.put(WorkflowState.REACT_STOP_REASON, "");
            updates.put(WorkflowState.ERROR, "");
            updates.put(WorkflowState.TASK_CLARIFY, false);
            updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
            updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
            log.info("[react-plan] call_tool, step={}, tool={}, argKeys={}, reason={}, costMs={}",
                    state.reactStep() + 1, tool, args.keySet(), reason,
                    System.currentTimeMillis() - start);
            return updates;
        } catch (BizException e) {
            log.warn("[react-plan] llm failed: {}", e.getMessage());
            return stop("规划模型不可用，基于已有证据回答");
        } catch (Exception e) {
            log.error("[react-plan] unexpected error", e);
            return stop("规划异常，基于已有证据回答");
        }
    }

    /** 拼装用户目标、工具目录和已有观察；不注入长篇历史，避免规划上下文膨胀。 */
    private String buildUserPrompt(WorkflowState state, List<ToolDefinition> tools, int maxSteps) {
        StringBuilder builder = new StringBuilder();
        builder.append("用户目标：\n").append(state.effectiveQuery()).append("\n\n");
        builder.append("可用只读工具：\n");
        for (ToolDefinition tool : tools) {
            builder.append("- ").append(tool.name()).append("：")
                    .append(tool.description()).append("\n")
                    .append("  参数 Schema：").append(tool.inputSchema()).append("\n");
        }
        builder.append("\n已执行步数：").append(state.reactStep())
                .append("，最多允许：").append(maxSteps).append("\n");
        builder.append("已有观察：\n");
        List<Map<String, Object>> history = state.reactHistory();
        if (history.isEmpty()) {
            builder.append("（无）\n");
        } else {
            int index = 1;
            for (Map<String, Object> item : history) {
                builder.append(index++).append(". 工具=").append(item.get("tool"))
                        .append("，参数=").append(JsonUtil.toJson(item.get("args")))
                        .append("，成功=").append(item.get("success"))
                        .append("，数据条数=").append(item.get("dataCount"));
                Object errorCode = item.get("errorCode");
                if (errorCode != null && !String.valueOf(errorCode).isBlank()) {
                    builder.append("，错误码=").append(errorCode);
                }
                builder.append("\n   结果=").append(item.get("summary")).append('\n');
            }
        }
        builder.append("\n请只输出 JSON：")
                .append("{\"action\":\"call_tool\",\"tool\":\"工具名\",\"args\":{},\"reason\":\"一句话\"}")
                .append(" 或 {\"action\":\"final\",\"reason\":\"一句话\"}。");
        return builder.toString();
    }

    /** 参数只接受 JSON 对象；空对象或缺失都视为无参数。 */
    private Map<String, Object> parseArgs(JsonNode argsNode) {
        if (argsNode == null || !argsNode.isObject()) {
            return Map.of();
        }
        Map<String, Object> args = JsonUtil.mapper().convertValue(
                argsNode, new TypeReference<Map<String, Object>>() {
                });
        return args == null ? Map.of() : args;
    }

    /** 正常结束：不写 error，让回答节点基于已有证据收口。 */
    private Map<String, Object> stop(String reason) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.REACT_DONE, true);
        updates.put(WorkflowState.REACT_STOP_REASON, reason == null ? "" : reason);
        updates.put(WorkflowState.REACT_ACTION, Map.of());
        updates.put(WorkflowState.ERROR, "");
        return updates;
    }

    /** 配置或前置状态错误：交给路由进入兜底。 */
    private Map<String, Object> fail(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        updates.put(WorkflowState.REACT_DONE, true);
        updates.put(WorkflowState.REACT_STOP_REASON, message);
        return updates;
    }
}
