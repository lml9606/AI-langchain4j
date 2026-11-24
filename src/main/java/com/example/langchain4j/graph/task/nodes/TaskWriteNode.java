package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.TaskOperation;
import com.example.langchain4j.graph.task.TaskTools;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
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
 * 固定写节点：只调用 {@code complete_demo_record}，且必须已确认并携带幂等键。
 *
 * 正常聊天链路不会直接进入本节点：写操作必须先经过 TaskConfirmNode 生成确认卡片，
 * 用户点击确认后由 TaskConfirmService 携带 approved=true 再次进入。
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
     * 执行一次已确认的写工具；重复确认由 ai_tool_execution 的唯一幂等键拦截。
     * 业务失败保留失败轨迹，回答节点据此说明原因，不返回假的成功结果。
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

        long start = System.currentTimeMillis();
        JsonNode args = JsonUtil.mapper().valueToTree(state.taskArgs());
        ToolContext context = new ToolContext(state.userId(), state.conversationId(),
                true, state.taskIdempotencyKey());
        log.info("[task-write] start, tool={}, argKeys={}, executionTail={}, conversation={}",
                TaskTools.WRITE, state.taskArgs().keySet(),
                tail(state.taskIdempotencyKey()), state.conversationId());

        ToolResult result = toolExecutor.invoke(context, TaskTools.WRITE, args);
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceItem(TaskTools.WRITE, state.taskArgs(), result));
        List<Map<String, Object>> evidence = evidence(result);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.TASK_TRACE, trace);
        updates.put(WorkflowState.TASK_EVIDENCE, evidence);
        updates.put(WorkflowState.TASK_CLARIFY, false);
        updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
        updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
        updates.put(WorkflowState.ERROR, result.success() ? "" : result.errorMessage());
        log.info("[task-write] done, success={}, errorCode={}, executionTail={}, costMs={}",
                result.success(), result.errorCode(), tail(state.taskIdempotencyKey()),
                System.currentTimeMillis() - start);
        return updates;
    }

    /** 写操作也保留轨迹；即使没有证据，回答节点也能解释成功或失败原因。 */
    private Map<String, Object> traceItem(String tool, Map<String, Object> args, ToolResult result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tool", tool);
        item.put("args", args);
        item.put("purpose", "把指定演示记录标记为完成");
        item.put("success", result.success());
        item.put("summary", Texts.truncate(result.summary(),
                properties.getLimits().getTaskSummaryMaxLength()));
        item.put("errorCode", result.errorCode());
        item.put("costMs", result.costMs());
        return item;
    }

    private List<Map<String, Object>> evidence(ToolResult result) {
        List<Map<String, Object>> evidence = new ArrayList<>();
        if (!result.success()) {
            return evidence;
        }
        for (ToolEvidence item : result.evidence()) {
            if (evidence.size() >= properties.getLimits().getTaskEvidenceMaxEntries()) {
                break;
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
        return evidence;
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        return updates;
    }

    private String tail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
