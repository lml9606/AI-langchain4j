package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.model.TaskOperation;
import com.example.langchain4j.graph.task.react.ReactProtocol;
import com.example.langchain4j.graph.task.react.ReactToolCatalog;
import com.example.langchain4j.graph.task.support.TaskReadResults;
import com.example.langchain4j.tool.api.constant.ToolErrorCode;
import com.example.langchain4j.tool.api.model.ToolContext;
import com.example.langchain4j.tool.api.model.ToolResult;
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

    /** 注入工具执行器、ReAct 白名单和步数配置；节点不保存跨请求状态。 */
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
        // 步骤1：只处理 REACT；固定读取和写操作不能通过错误路由混入 ReAct 循环。
        if (TaskOperation.parse(state.taskOperation()) != TaskOperation.REACT) {
            return fail("ReAct 工具节点收到非 REACT 操作，已拒绝执行");
        }
        // 步骤2：执行前重新检查开关和终止状态，兼容 checkpoint 恢复和旧状态重放。
        if (!properties.getReact().isEnabled()) {
            return fail("ReAct 只读诊断当前未启用");
        }
        if (state.reactDone()) {
            return stop(state.reactStopReason());
        }

        // 步骤3：工具节点再做一次步数防御，避免绕过规划节点直接进入执行节点。
        int maxSteps = Math.max(1, properties.getReact().getMaxSteps());
        if (state.reactStep() >= maxSteps) {
            return stop("已达到最大工具步数 " + maxSteps + "，基于已有证据回答");
        }

        // 步骤4：REACT_ACTION 是规划节点产出的一次性任务单；没有有效动作就结束循环。
        Map<String, Object> action = state.reactAction();
        String tool = text(action, ReactProtocol.FIELD_TOOL);
        String reason = text(action, ReactProtocol.FIELD_REASON);
        if (!ReactProtocol.isCallTool(text(action, ReactProtocol.FIELD_ACTION)) || tool.isBlank()) {
            return stop("没有待执行的工具动作，基于已有证据回答");
        }
        // 步骤5：执行前第三次校验工具白名单，防止旧动作或误路由绕过授权。
        if (!toolCatalog.allows(tool)) {
            log.warn("[react-tool] tool not allowed, tool={}, step={}", tool, state.reactStep());
            return stop("工具不在 ReAct 白名单中，已停止调用");
        }
        // 步骤6：用户身份只从状态读取，模型参数不能覆盖或伪造数据归属。
        if (state.userId() == null || state.userId().isBlank()) {
            return fail("缺少用户身份，拒绝执行 ReAct 工具");
        }

        Map<String, Object> args = mapValue(action.get(ReactProtocol.FIELD_ARGS));
        // 步骤7：参数转成标准 JSON 后生成稳定签名，对象字段顺序不同也会识别为重复调用。
        JsonNode argsNode = JsonUtil.mapper().valueToTree(args);
        String signature = ReactToolCatalog.signature(tool, argsNode);
        if (alreadyCalled(state.reactHistory(), signature)) {
            log.warn("[react-tool] duplicate call, tool={}, args={}, step={}",
                    tool, JsonUtil.toJson(args), state.reactStep());
            return stop("检测到相同工具和相同参数的重复调用，已停止循环");
        }

        long start = System.currentTimeMillis();
        // 步骤8：必须经过 ToolExecutor，权限、Schema、限流、线程隔离和超时不能被绕过。
        ToolContext context = new ToolContext(state.userId(), state.conversationId());
        ToolResult result = toolExecutor.invoke(context, tool, argsNode);

        // 步骤9：一次工具结果拆成业务数据、轨迹、证据和规划观察，分别写回不同状态字段。
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

        // 步骤10：区分“致命错误”和“无数据”；两者都停止循环，但保留轨迹供回答节点解释。
        boolean fatal = !result.success() && FATAL_ERROR_CODES.contains(result.errorCode());
        boolean noData = result.success() && dataRows.isEmpty();
        String stopReason = fatal ? "工具执行遇到不可恢复错误，已停止循环"
                : noData ? "工具没有返回数据，已停止继续调用" : "";

        // 步骤11：写回状态并清空一次性动作；工具失败不写 ERROR，由回答节点按轨迹解释。
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
        // 观察只保留下一步规划需要的信息，完整结果留给 taskData 和 taskEvidence。
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("step", step);
        item.put(ReactProtocol.FIELD_TOOL, tool);
        item.put(ReactProtocol.FIELD_ARGS, args);
        item.put(ReactProtocol.FIELD_REASON, reason);
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
        // 签名在工具执行前计算并写入历史，后续只做字符串比较，避免重新序列化参数。
        for (Map<String, Object> item : history) {
            if (signature.equals(text(item, "signature"))) {
                return true;
            }
        }
        return false;
    }

    /** 追加一次工具执行轨迹，并按配置上限保留最早的部分，避免 checkpoint 无限增长。 */
    private List<Map<String, Object>> appendTrace(List<Map<String, Object>> existing,
                                                  Map<String, Object> item) {
        // 轨迹有上限，防止异常循环或配置调大后把 checkpoint 撑大。
        List<Map<String, Object>> result = new ArrayList<>(existing);
        result.add(item);
        int max = properties.getLimits().getTaskTraceMaxEntries();
        return result.size() <= max ? result : new ArrayList<>(result.subList(0, max));
    }

    /** 追加工具返回的证据片段，并按配置上限截断，保证回答阶段只引用已保留的证据。 */
    private List<Map<String, Object>> appendEvidence(List<Map<String, Object>> existing,
                                                     List<Map<String, Object>> items) {
        // 证据按配置截断；回答节点只使用可用证据，不能引用未保留的内容。
        List<Map<String, Object>> result = new ArrayList<>(existing);
        result.addAll(items);
        int max = properties.getLimits().getTaskEvidenceMaxEntries();
        return result.size() <= max ? result : new ArrayList<>(result.subList(0, max));
    }

    /** 追加业务明细行，并限制总行数，防止单轮响应或 checkpoint 体积失控。 */
    private List<Map<String, Object>> appendData(List<Map<String, Object>> existing,
                                                 List<Map<String, Object>> items) {
        // 业务明细给前端或后续业务消费，设置硬上限避免单轮响应无限膨胀。
        List<Map<String, Object>> result = new ArrayList<>(existing);
        result.addAll(items);
        return result.size() <= MAX_DATA_ROWS ? result : new ArrayList<>(result.subList(0, MAX_DATA_ROWS));
    }

    /** 追加一次规划观察；历史长度由最大步数约束，正常不会超过配置上限。 */
    private List<Map<String, Object>> appendHistory(List<Map<String, Object>> existing,
                                                    Map<String, Object> item) {
        // 历史长度受最大步数约束，正常不会超过配置上限，因此这里只追加不截断。
        List<Map<String, Object>> result = new ArrayList<>(existing);
        result.add(item);
        return result;
    }

    /**
     * 把 Action 的 args 统一成字符串键 Map。
     * checkpoint 反序列化后可能是任意 Map 实现；非 Map 脏数据按空参数处理，
     * 字段类型和必填校验仍交给 ToolExecutor 的 Schema。
     */
    private Map<String, Object> mapValue(Object value) {
        // 不是 Map 的脏数据按空参数处理；具体字段合法性仍由 ToolExecutor 的 Schema 校验。
        if (!(value instanceof Map<?, ?> map)) {
            return Map.of();
        }
        Map<String, Object> result = new LinkedHashMap<>(map.size());
        map.forEach((key, item) -> result.put(String.valueOf(key), item));
        return result;
    }

    /** 读取状态中的文本值；缺失或类型漂移时统一转成去空白字符串。 */
    private String text(Map<String, Object> map, String key) {
        // 状态字段可能因 checkpoint 反序列化变成其他类型，统一转字符串并 trim。
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    /**
     * 正常停止 ReAct 循环：不写 error，由回答节点解释已有证据或失败轨迹。
     * 同时清空一次性动作，保证 checkpoint 恢复或重复路由时不会再次执行已消费的动作。
     */
    private Map<String, Object> stop(String reason) {
        Map<String, Object> updates = new LinkedHashMap<>();
        // 清空一次性动作，保证恢复或重复路由时不会再次执行已经消费的任务。
        updates.put(WorkflowState.REACT_DONE, true);
        updates.put(WorkflowState.REACT_STOP_REASON, reason == null ? "" : reason);
        updates.put(WorkflowState.REACT_ACTION, Map.of());
        updates.put(WorkflowState.ERROR, "");
        return updates;
    }

    /**
     * ReAct 前置条件错误：写图级 ERROR 并终止循环，由 Router 进入 fallback。
     * 工具本身的业务失败不走这里，而是作为观察写回轨迹。
     */
    private Map<String, Object> fail(String message) {
        // 图级错误写 ERROR，让 Router 进入 fallback；工具本身的失败仍走正常观察链路。
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        updates.put(WorkflowState.REACT_DONE, true);
        updates.put(WorkflowState.REACT_STOP_REASON, message);
        return updates;
    }
}
