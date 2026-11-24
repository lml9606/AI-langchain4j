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
 * 固定只读节点：只调用 {@code query_demo_record}，不读取模型选择的工具名。
 *
 * 参数由 TaskResolveNode 抽取；这里只负责执行、收集轨迹和证据。
 * 工具业务失败也会保留轨迹并交给回答节点解释，不会把半份结果伪装成成功。
 */
@Slf4j
@Component
public class TaskReadNode implements NodeAction<WorkflowState> {

    private final ToolExecutor toolExecutor;
    private final AgentProperties properties;

    public TaskReadNode(ToolExecutor toolExecutor, AgentProperties properties) {
        this.toolExecutor = toolExecutor;
        this.properties = properties;
    }

    /**
     * 以未确认、无幂等键的上下文执行只读工具，返回轨迹与证据。
     * 只读节点不设置 pendingAction，前端不会出现确认卡片。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        if (TaskOperation.parse(state.taskOperation()) != TaskOperation.READ) {
            return fail("只读节点收到非 READ 操作，已拒绝执行");
        }
        long start = System.currentTimeMillis();
        JsonNode args = JsonUtil.mapper().valueToTree(state.taskArgs());
        ToolContext context = new ToolContext(state.userId(), state.conversationId(), false, "");
        log.info("[task-read] start, tool={}, argKeys={}, conversation={}",
                TaskTools.READ, state.taskArgs().keySet(), state.conversationId());

        ToolResult result = toolExecutor.invoke(context, TaskTools.READ, args);
        List<Map<String, Object>> trace = new ArrayList<>();
        trace.add(traceItem(TaskTools.READ, state.taskArgs(), result));
        List<Map<String, Object>> evidence = evidence(result);

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.TASK_TRACE, trace);
        updates.put(WorkflowState.TASK_EVIDENCE, evidence);
        updates.put(WorkflowState.TASK_CLARIFY, false);
        updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
        updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
        updates.put(WorkflowState.ERROR, result.success() ? "" : result.errorMessage());
        log.info("[task-read] done, success={}, evidence={}, errorCode={}, costMs={}",
                result.success(), evidence.size(), result.errorCode(),
                System.currentTimeMillis() - start);
        return updates;
    }

    /** 轨迹保存工具名、参数快照和结果摘要，供回答模型与前端排查使用。 */
    private Map<String, Object> traceItem(String tool, Map<String, Object> args, ToolResult result) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tool", tool);
        item.put("args", args);
        item.put("purpose", "查询当前用户的演示记录");
        item.put("success", result.success());
        item.put("summary", Texts.truncate(result.summary(),
                properties.getLimits().getTaskSummaryMaxLength()));
        item.put("errorCode", result.errorCode());
        item.put("costMs", result.costMs());
        return item;
    }

    /** 只保留上限内的证据，避免一条查询返回过多内容撑大回答 prompt。 */
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
}
