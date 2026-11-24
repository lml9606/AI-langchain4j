package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.ReactToolCatalog;
import com.example.langchain4j.graph.task.TaskOperation;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.runtime.ToolErrorCode;
import com.example.langchain4j.tool.runtime.ToolExecutor;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * ReAct 工具执行节点：执行规划节点选出的一个白名单只读工具，并把观察写回状态。
 *
 * 关键约束：
 * 1. 工具必须来自 ReactToolCatalog，写工具永远不可达；
 * 2. 必须经过 ToolExecutor，权限、Schema、限流和超时不能被绕过；
 * 3. 工具失败是诊断观察而不是图异常，回答节点仍需基于轨迹解释；
 * 4. 相同工具相同参数、无数据、致命错误或达到步数上限时停止循环。
 */
@Slf4j
@Component
public class ReactToolNode implements NodeAction<WorkflowState> {

    private static final int MAX_DATA_ROWS = 100;
    private static final Set<String> FATAL_ERROR_CODES = Set.of(
            ToolErrorCode.UNKNOWN_TOOL.name(),
            ToolErrorCode.CONFIRMATION_REQUIRED.name(),
            ToolErrorCode.MISSING_IDEMPOTENCY_KEY.name(),
            ToolErrorCode.FORBIDDEN_WRITE.name(),
            ToolErrorCode.FORBIDDEN_TOOL.name(),
            ToolErrorCode.RATE_LIMITED.name(),
            ToolErrorCode.OVERLOADED.name(),
            ToolErrorCode.INTERRUPTED.name());

    private final ToolExecutor toolExecutor;
    private final ReactToolCatalog toolCatalog;
    private final AgentProperties properties;

    public ReactToolNode(ToolExecutor toolExecutor,
                         ReactToolCatalog toolCatalog,
                         AgentProperties properties) {
        this.toolExecutor = toolExecutor;
        this.toolCatalog = toolCatalog;
        this.properties = properties;
    }

    /**
     * 执行一次工具并返回增量状态。
     *
     * 所有可预期问题都转成停止原因或失败轨迹，不向图抛裸异常；只有配置或前置状态
     * 明显错误时才写 error，交给路由进入兜底。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        if (TaskOperation.parse(state.taskOperation()) != TaskOperation.REACT) {
            return fail("ReAct 工具节点收到非 REACT 操作，已拒绝执行");
        }
        if (!properties.getReact().isEnabled()) {
            return fail("ReAct 只读诊断当前未启用");
        }
        if (state.reactDone()) {
            return stop(state.reactStopReason());
        }

        int maxSteps = Math.max(1, properties.getReact().getMaxSteps());
        if (state.reactStep() >= maxSteps) {
            return stop("已达到最大工具步数 " + maxSteps + "，基于已有证据回答");
        }

        Map<String, Object> action = state.reactAction();
        String tool = text(action, "tool");
        String reason = text(action, "reason");
        if (!"call_tool".equals(text(action, "action")) || tool.isBlank()) {
            return stop("没有待执行的工具动作，基于已有证据回答");
        }
        if (!toolCatalog.allows(tool)) {
            log.warn("[react-tool] tool not allowed, tool={}, step={}", tool, state.reactStep());
            return stop("工具不在 ReAct 白名单中，已停止调用");
        }
        if (state.userId() == null || state.userId().isBlank()) {
            return fail("缺少用户身份，拒绝执行 ReAct 工具");
        }

        Map<String, Object> args = mapValue(action.get("args"));
        JsonNode argsNode = JsonUtil.mapper().valueToTree(args);
        String signature = ReactToolCatalog.signature(tool, argsNode);
        if (alreadyCalled(state.reactHistory(), signature)) {
            log.warn("[react-tool] duplicate call, tool={}, args={}, step={}",
                    tool, JsonUtil.toJson(args), state.reactStep());
            return stop("检测到相同工具和相同参数的重复调用，已停止循环");
        }

        long start = System.currentTimeMillis();
        ToolContext context = new ToolContext(state.userId(), state.conversationId());
        ToolResult result = toolExecutor.invoke(context, tool, argsNode);

        int nextStep = state.reactStep() + 1;
        List<Map<String, Object>> dataRows = TaskReadResults.dataRows(result);
        List<Map<String, Object>> trace = appendTrace(state.taskTrace(),
                TaskReadResults.traceItem(tool, args,
                        reason.isBlank() ? "ReAct 只读诊断" : reason, result, properties));
        List<Map<String, Object>> evidence = appendEvidence(state.taskEvidence(),
                TaskReadResults.evidence(result, properties));
        List<Map<String, Object>> data = ReactToolCatalog.contributesBusinessData(tool)
                ? appendData(state.taskData(), dataRows) : state.taskData();
        List<Map<String, Object>> history = appendHistory(state.reactHistory(), observation(
                nextStep, tool, args, reason, signature, result, dataRows.size()));

        boolean fatal = !result.success() && FATAL_ERROR_CODES.contains(result.errorCode());
        boolean noData = result.success() && dataRows.isEmpty();
        String stopReason = fatal ? "工具执行遇到不可恢复错误，已停止循环"
                : noData ? "工具没有返回数据，已停止继续调用" : "";

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.REACT_STEP, nextStep);
        updates.put(WorkflowState.REACT_ACTION, Map.of());
        updates.put(WorkflowState.REACT_HISTORY, history);
        updates.put(WorkflowState.REACT_DONE, fatal || noData);
        updates.put(WorkflowState.REACT_STOP_REASON, stopReason);
        updates.put(WorkflowState.TASK_TRACE, trace);
        updates.put(WorkflowState.TASK_EVIDENCE, evidence);
        updates.put(WorkflowState.TASK_DATA, data);
        updates.put(WorkflowState.TASK_CLARIFY, false);
        updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
        updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
        updates.put(WorkflowState.ERROR, "");
        log.info("[react-tool] done, step={}, tool={}, success={}, rows={}, evidence={}, "
                        + "fatal={}, done={}, stopReason={}, errorCode={}, costMs={}",
                nextStep, tool, result.success(), dataRows.size(), evidence.size(), fatal,
                fatal || noData, stopReason, result.errorCode(),
                System.currentTimeMillis() - start);
        return updates;
    }

    /** 组合一次工具执行的结构化观察，供下一步规划和最终回答复用。 */
    private Map<String, Object> observation(int step, String tool, Map<String, Object> args,
                                            String reason, String signature,
                                            ToolResult result, int dataCount) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("step", step);
        item.put("tool", tool);
        item.put("args", args);
        item.put("reason", reason);
        item.put("signature", signature);
        item.put("success", result.success());
        item.put("summary", Texts.truncate(result.summary(),
                properties.getLimits().getTaskSummaryMaxLength()));
        item.put("errorCode", result.errorCode());
        item.put("errorMessage", Texts.truncate(result.errorMessage(),
                properties.getLimits().getTaskSummaryMaxLength()));
        item.put("dataCount", dataCount);
        item.put("costMs", result.costMs());
        return item;
    }

    /** 判断历史中是否已经出现过同工具同参数调用。 */
    private boolean alreadyCalled(List<Map<String, Object>> history, String signature) {
        for (Map<String, Object> item : history) {
            if (signature.equals(text(item, "signature"))) {
                return true;
            }
        }
        return false;
    }

    private List<Map<String, Object>> appendTrace(List<Map<String, Object>> existing,
                                                  Map<String, Object> item) {
        List<Map<String, Object>> result = new ArrayList<>(existing);
        result.add(item);
        int max = properties.getLimits().getTaskTraceMaxEntries();
        return result.size() <= max ? result : new ArrayList<>(result.subList(0, max));
    }

    private List<Map<String, Object>> appendEvidence(List<Map<String, Object>> existing,
                                                     List<Map<String, Object>> items) {
        List<Map<String, Object>> result = new ArrayList<>(existing);
        result.addAll(items);
        int max = properties.getLimits().getTaskEvidenceMaxEntries();
        return result.size() <= max ? result : new ArrayList<>(result.subList(0, max));
    }

    private List<Map<String, Object>> appendData(List<Map<String, Object>> existing,
                                                 List<Map<String, Object>> items) {
        List<Map<String, Object>> result = new ArrayList<>(existing);
        result.addAll(items);
        return result.size() <= MAX_DATA_ROWS ? result : new ArrayList<>(result.subList(0, MAX_DATA_ROWS));
    }

    private List<Map<String, Object>> appendHistory(List<Map<String, Object>> existing,
                                                    Map<String, Object> item) {
        List<Map<String, Object>> result = new ArrayList<>(existing);
        result.add(item);
        return result;
    }

    /** Action 的 args 在 checkpoint 反序列化后可能是任意 Map 实现，统一转字符串键。 */
    private Map<String, Object> mapValue(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(map.size());
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    private String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 正常停止：不写 error，由回答节点解释已有证据或失败轨迹。 */
    private Map<String, Object> stop(String reason) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.REACT_DONE, true);
        updates.put(WorkflowState.REACT_STOP_REASON, reason == null ? "" : reason);
        updates.put(WorkflowState.REACT_ACTION, Map.of());
        updates.put(WorkflowState.ERROR, "");
        return updates;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        updates.put(WorkflowState.REACT_DONE, true);
        updates.put(WorkflowState.REACT_STOP_REASON, message);
        return updates;
    }
}
