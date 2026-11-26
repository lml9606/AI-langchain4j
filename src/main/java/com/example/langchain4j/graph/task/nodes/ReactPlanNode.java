package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.context.Allocation;
import com.example.langchain4j.common.context.BlockType;
import com.example.langchain4j.common.context.ContextAssembler;
import com.example.langchain4j.common.context.ContextBlock;
import com.example.langchain4j.common.context.ContextScene;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.model.TaskOperation;
import com.example.langchain4j.graph.task.react.ReactProtocol;
import com.example.langchain4j.graph.task.react.ReactToolCatalog;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.model.LlmRequest;
import com.example.langchain4j.prompt.PromptTemplates;
import com.example.langchain4j.prompt.PromptTemplateService;
import com.example.langchain4j.tool.api.model.ToolDefinition;
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
 * ReAct 规划节点：让模型在受控工具目录中选择下一步，或宣布结束。
 *
 * 模型只产出结构化动作，不执行工具；工具白名单、参数形状、步数和停止条件都在这里
 * 先做一次防御校验。真正的权限、Schema、限流和超时仍由 ToolExecutor 在执行节点复核。
 */
@Slf4j
@Component
public class ReactPlanNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;
    private final PromptTemplateService promptTemplateService;
    private final ReactToolCatalog toolCatalog;
    private final AgentProperties properties;
    private final ContextAssembler contextAssembler;

    /** 注入模型网关、提示词服务、ReAct 白名单、步数配置和上下文预算装配器；规划节点本身无状态。 */
    public ReactPlanNode(LlmGateway llmGateway,
                         PromptTemplateService promptTemplateService,
                         ReactToolCatalog toolCatalog,
                         AgentProperties properties,
                         ContextAssembler contextAssembler) {
        this.llmGateway = llmGateway;
        this.promptTemplateService = promptTemplateService;
        this.toolCatalog = toolCatalog;
        this.properties = properties;
        this.contextAssembler = contextAssembler;
    }

    /**
     * 生成下一步动作：call_tool 进入工具执行节点，final 或任何停止条件进入回答节点。
     * 规划输出不可解析时按“已有证据足够”处理，避免把一次格式错误升级为整轮失败。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：确认当前节点只处理 REACT，避免错误路由把固定读取或写操作带入自由工具循环。
        if (TaskOperation.parse(state.taskOperation()) != TaskOperation.REACT) {
            return fail("ReAct 规划节点收到非 REACT 操作，已拒绝执行");
        }
        // 步骤2：配置开关在运行期再次校验，防止任务解析后配置被关闭或旧 checkpoint 重放。
        if (!properties.getReact().isEnabled()) {
            return fail("ReAct 只读诊断当前未启用");
        }
        // 步骤3：上一轮已经决定结束时直接短路，避免重复调用模型。
        if (state.reactDone()) {
            return stop(state.reactStopReason());
        }

        // 步骤4：先检查步数再调用模型；达到上限后不再产生新的规划请求。
        int maxSteps = Math.max(1, properties.getReact().getMaxSteps());
        if (state.reactStep() >= maxSteps) {
            log.info("[react-plan] max steps reached, step={}, max={}", state.reactStep(), maxSteps);
            return stop("已达到最大工具步数 " + maxSteps + "，基于已有证据回答");
        }

        long start = System.currentTimeMillis();
        // 步骤5：只把白名单只读工具的定义交给模型，模型看不到全部注册工具，也没有写工具。
        List<ToolDefinition> tools = toolCatalog.definitions();
        if (tools.isEmpty()) {
            return fail("ReAct 没有可用的只读工具，请检查工具注册与启用状态");
        }
        try {
            // 步骤6：调用模型只生成结构化动作；节点本身不执行数据库查询或工具副作用。
            String systemPrompt = promptTemplateService.resolve(
                    "REACT_PLAN_SYSTEM", PromptTemplates.REACT_PLAN_SYSTEM);
            JsonNode node = llmGateway.generateJson(
                    LlmRequest.of(state.model(), systemPrompt, buildUserPrompt(state, tools, maxSteps))
                            .maxOutputTokens(Math.max(1,
                                    properties.getReact().getPlanMaxOutputTokens()))
                            .scene("react-plan"));
            if (node == null) {
                return stop("规划输出为空或不是合法 JSON，基于已有证据回答");
            }

            // 步骤7：先识别 final；正常结束不是异常，后续由回答节点基于已有证据收口。
            String action = ReactProtocol.normalizeAction(
                    node.path(ReactProtocol.FIELD_ACTION).asText(""));
            String reason = Texts.truncate(node.path(ReactProtocol.FIELD_REASON).asText("").trim(),
                    Math.max(1, properties.getReact().getReasonMaxLength()));
            if (ReactProtocol.isFinal(action)) {
                log.info("[react-plan] final, step={}, reason={}, costMs={}",
                        state.reactStep(), reason, System.currentTimeMillis() - start);
                return stop(reason.isBlank() ? "模型判断已有证据足够" : reason);
            }
            if (!ReactProtocol.isCallTool(action)) {
                return stop("规划动作无效，基于已有证据回答");
            }

            // 步骤8：工具名必须在白名单内；这里先拒绝，执行节点还会再做一次最终校验。
            String tool = node.path(ReactProtocol.FIELD_TOOL).asText("").trim();
            if (!toolCatalog.allows(tool)) {
                log.warn("[react-plan] tool not allowed, tool={}, step={}", tool, state.reactStep());
                return stop("模型选择了未授权的工具，已停止继续调用");
            }
            // 步骤9：参数只接受 JSON 对象；具体字段和类型交给 ToolExecutor 的 Schema 校验。
            JsonNode argsNode = node.get(ReactProtocol.FIELD_ARGS);
            if (argsNode != null && !argsNode.isNull() && !argsNode.isObject()) {
                return stop("工具参数不是 JSON 对象，基于已有证据回答");
            }
            Map<String, Object> args = parseArgs(argsNode);

            // 步骤10：把模型输出整理成一次性动作，动作只保留可审计的 reason，不保存完整思维链。
            Map<String, Object> planned = new LinkedHashMap<>();
            planned.put(ReactProtocol.FIELD_ACTION, ReactProtocol.ACTION_CALL_TOOL);
            planned.put("step", state.reactStep() + 1);
            planned.put(ReactProtocol.FIELD_TOOL, tool);
            planned.put(ReactProtocol.FIELD_ARGS, args);
            planned.put(ReactProtocol.FIELD_REASON, reason);

            // 步骤11：写回状态增量；REACT_ACTION 是交给 react_tool 消费的任务单。
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
            // 步骤12：模型超时、限流或调用失败时保留已有证据，转为正常停止而不是图级异常。
            log.warn("[react-plan] llm failed: {}", e.getMessage());
            return stop("规划模型不可用，基于已有证据回答");
        } catch (Exception e) {
            log.error("[react-plan] unexpected error", e);
            return stop("规划异常，基于已有证据回答");
        }
    }

    /** 拼装用户目标、工具目录和已有观察；不注入长篇历史，避免规划上下文膨胀。 */
    private String buildUserPrompt(WorkflowState state, List<ToolDefinition> tools, int maxSteps) {
        // 工具目录只展示名称、描述和 Schema，让模型知道能做什么以及参数边界
        StringBuilder catalog = new StringBuilder();
        for (ToolDefinition tool : tools) {
            catalog.append("- ").append(tool.name()).append("：")
                    .append(tool.description()).append("\n")
                    .append("  参数 Schema：").append(tool.inputSchema()).append("\n");
        }
        // 观察历史只放执行摘要和状态，不放完整业务数据，控制长度和敏感信息暴露
        List<String> observations = new ArrayList<>();
        int index = 1;
        for (Map<String, Object> item : state.reactHistory()) {
            StringBuilder line = new StringBuilder();
            line.append(index++).append(". 工具=")
                    .append(item.get(ReactProtocol.FIELD_TOOL))
                    .append("，参数=").append(JsonUtil.toJson(item.get(ReactProtocol.FIELD_ARGS)))
                    .append("，成功=").append(item.get("success"))
                    .append("，数据条数=").append(item.get("dataCount"));
            Object errorCode = item.get("errorCode");
            if (errorCode != null && !String.valueOf(errorCode).isBlank()) {
                line.append("，错误码=").append(errorCode);
            }
            line.append("\n   结果=").append(item.get("summary"));
            observations.add(line.toString());
        }
        // 目标与步数是规划前提（强制块），工具目录可截断，观察历史超预算时丢弃最旧步骤
        Allocation allocation = contextAssembler.allocate(ContextScene.REACT_PLAN, List.of(
                ContextBlock.single(BlockType.QUERY,
                        "用户目标：\n" + state.effectiveQuery()
                                + "\n\n已执行步数：" + state.reactStep()
                                + "，最多允许：" + maxSteps),
                ContextBlock.single(BlockType.TOOLS, catalog.toString()),
                ContextBlock.of(BlockType.HISTORY, observations)));
        return allocation.content(BlockType.QUERY)
                + "\n\n可用只读工具：\n" + allocation.content(BlockType.TOOLS)
                + "\n已有观察：\n"
                + (observations.isEmpty() ? "（无）"
                        : String.join("\n", allocation.segments(BlockType.HISTORY)));
    }

    /** 参数只接受 JSON 对象；空对象或缺失都视为无参数。 */
    private Map<String, Object> parseArgs(JsonNode argsNode) {
        // 非对象参数不做隐式转换，避免把数组或标量误当成工具参数。
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
        // 清空一次性动作，避免 checkpoint 恢复后再次执行旧计划。
        updates.put(WorkflowState.REACT_DONE, true);
        updates.put(WorkflowState.REACT_STOP_REASON, reason == null ? "" : reason);
        updates.put(WorkflowState.REACT_ACTION, Map.of());
        updates.put(WorkflowState.ERROR, "");
        return updates;
    }

    /** 配置或前置状态错误：交给路由进入兜底。 */
    private Map<String, Object> fail(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        // 这类错误无法通过已有证据解释，写 error 让 Router 进入 fallback。
        updates.put(WorkflowState.ERROR, message);
        updates.put(WorkflowState.REACT_DONE, true);
        updates.put(WorkflowState.REACT_STOP_REASON, message);
        return updates;
    }
}
