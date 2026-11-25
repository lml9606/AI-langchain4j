package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.model.TaskOperation;
import com.example.langchain4j.tool.api.constant.ToolNames;
import com.example.langchain4j.tool.api.model.ToolContext;
import com.example.langchain4j.tool.api.model.ToolEvidence;
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

/**
 * 固定写节点：按已确认的计划顺序执行白名单写工具。
 *
 * 正常聊天链路不会直接进入本节点：写操作必须先经过 TaskConfirmNode 生成确认卡片，
 * 用户点击确认后由 TaskConfirmService 携带 approved=true 再次进入。
 *
 * 多步语义：每步使用独立幂等键 confirmationId#stepNo；前面步骤成功、后面步骤失败时
 * 不回滚已提交步骤（业务写已各自提交），失败步骤由运行台账记录，重试会先重放已完成步骤。
 * 这里再次校验确认标记和幂等键，防止图被外部伪造或误路由后直接产生副作用。
 */
@Slf4j
@Component
public class TaskWriteNode implements NodeAction<WorkflowState> {

    private final ToolExecutor toolExecutor;
    private final AgentProperties properties;

    public TaskWriteNode(ToolExecutor toolExecutor, AgentProperties properties) {
        this.toolExecutor = toolExecutor;
        this.properties = properties;
    }

    /**
     * 按计划顺序执行已确认写操作，任一步失败立即停止。
     *
     * 每一步都由工具层的幂等闸门保护：重复确认时已完成步骤直接重放，未完成步骤继续执行。
     * 业务失败保留失败轨迹，回答节点据此说明失败原因，不返回假的成功结果。
     *
     * @param state 已带 approved=true 与 confirmationId 幂等键的状态
     * @return 工具轨迹、证据、错误标记；不直接写业务数据
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        if (TaskOperation.parse(state.taskOperation()) != TaskOperation.WRITE) {
            return fail("写节点收到非 WRITE 操作，已拒绝执行");
        }
        if (!state.taskApproved() || state.taskIdempotencyKey().isBlank()) {
            log.warn("[task-write] missing confirmation, approved={}, idempotencyKeyBlank={}",
                    state.taskApproved(), state.taskIdempotencyKey().isBlank());
            return fail("写操作需要用户确认后执行");
        }
        List<WriteStep> steps = resolveSteps(state);
        if (steps.isEmpty()) {
            return fail("写操作计划为空，已拒绝执行");
        }

        long start = System.currentTimeMillis();
        List<Map<String, Object>> trace = new ArrayList<>();
        List<Map<String, Object>> evidence = new ArrayList<>();
        String error = "";
        int completed = 0;
        for (WriteStep step : steps) {
            String executionKey = state.taskIdempotencyKey() + "#" + step.stepNo();
            JsonNode args = JsonUtil.mapper().valueToTree(step.args());
            ToolContext context = new ToolContext(state.userId(), state.conversationId(),
                    true, executionKey);
            ToolResult result = toolExecutor.invoke(context, step.tool(), args);
            trace.add(traceItem(step, result));
            appendEvidence(evidence, result);
            if (!result.success()) {
                error = result.errorMessage();
                log.warn("[task-write] step failed, step={}, tool={}, errorCode={}, tail={}",
                        step.stepNo(), step.tool(), result.errorCode(), tail(executionKey));
                break;
            }
            completed++;
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.TASK_TRACE, trace);
        updates.put(WorkflowState.TASK_EVIDENCE, evidence);
        updates.put(WorkflowState.TASK_CLARIFY, false);
        updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
        updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
        updates.put(WorkflowState.ERROR, error);
        log.info("[task-write] done, totalSteps={}, completed={}, success={}, costMs={}",
                steps.size(), completed, error.isBlank(), System.currentTimeMillis() - start);
        return updates;
    }

    /**
     * 解析执行步骤：优先使用确认时的多步计划，空计划用顶层 args 兜底单步。
     *
     * 只接受白名单写工具 complete_demo_record；未知工具直接跳过，防止越权副作用。
     */
    private List<WriteStep> resolveSteps(WorkflowState state) {
        List<WriteStep> steps = new ArrayList<>();
        for (Map<String, Object> item : state.taskPlan()) {
            String tool = text(item.get("tool"));
            Map<String, Object> args = map(item.get("args"));
            if (!ToolNames.COMPLETE_DEMO_RECORD.equals(tool) || args.isEmpty()) {
                continue;
            }
            steps.add(new WriteStep(steps.size() + 1, tool, args, text(item.get("summary"))));
        }
        if (steps.isEmpty() && !state.taskArgs().isEmpty()) {
            steps.add(new WriteStep(1, ToolNames.COMPLETE_DEMO_RECORD, state.taskArgs(), ""));
        }
        return steps;
    }

    /** 写操作也保留轨迹；即使没有证据，回答节点也能解释成功或失败原因。 */
    private Map<String, Object> traceItem(WriteStep step, ToolResult result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("stepNo", step.stepNo());
        item.put("tool", step.tool());
        item.put("args", step.args());
        item.put("purpose", step.summary().isBlank()
                ? "把指定演示记录标记为完成" : step.summary());
        item.put("success", result.success());
        item.put("summary", Texts.truncate(result.summary(),
                properties.getLimits().getTaskSummaryMaxLength()));
        item.put("errorCode", result.errorCode());
        item.put("costMs", result.costMs());
        return item;
    }

    /** 追加证据并遵守全局上限；失败结果不产生证据。 */
    private void appendEvidence(List<Map<String, Object>> evidence, ToolResult result) {
        if (!result.success()) {
            return;
        }
        for (ToolEvidence item : result.evidence()) {
            if (evidence.size() >= properties.getLimits().getTaskEvidenceMaxEntries()) {
                return;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", item.type());
            row.put("sourceId", item.sourceId());
            row.put("title", item.title());
            row.put("snippet", Texts.truncate(item.snippet(),
                    properties.getLimits().getTaskSummaryMaxLength()));
            row.put("score", item.score());
            evidence.add(row);
        }
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        return updates;
    }

    /** 对象转字符串；null 返回空串。 */
    private String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    /** 对象转字符串键 Map；类型不符返回空 Map。 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> source ? (Map<String, Object>) source : Map.of();
    }

    /** 日志脱敏：只输出步骤幂等键末尾 8 位。 */
    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }

    /** 一步写操作：stepNo 从 1 开始，与确认令牌和执行台账保持一致。 */
    private record WriteStep(int stepNo, String tool, Map<String, Object> args, String summary) {
    }
}
