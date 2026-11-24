package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.TaskReadTarget;
import com.example.langchain4j.graph.task.TaskTools;
import com.example.langchain4j.repository.entity.Quota;
import com.example.langchain4j.service.QuotaService;
import com.example.langchain4j.tool.api.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第二段只读节点：先查报名，再用报名单号查询关联额度。
 *
 * 只在 withQuotas=true、第一段报名已命中的情况下进入；查询直接调用领域 Service，
 * 并以 user_id 和 enrollment_no 双重过滤，避免跨用户读取额度。
 */
@Slf4j
@Component
public class TaskQuotaReadNode implements NodeAction<WorkflowState> {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final QuotaService quotaService;
    private final AgentProperties properties;

    public TaskQuotaReadNode(QuotaService quotaService, AgentProperties properties) {
        this.quotaService = quotaService;
        this.properties = properties;
    }

    /**
     * 使用第一段命中的报名单号查询额度，并把轨迹、证据和业务明细追加到已有结果。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        TaskReadTarget sourceTarget = TaskReadTarget.parse(state.taskSourceType());
        if (sourceTarget != TaskReadTarget.ENROLLMENT) {
            return fail("缺少报名来源，无法查询关联额度");
        }
        if (positiveLong(state.taskSourceId()) == null) {
            return fail("报名主键无效，无法查询关联额度");
        }
        String enrollmentNo = text(state.taskArgs(), "enrollmentNo");
        if (enrollmentNo.isBlank()) {
            return fail("缺少报名单号，无法查询关联额度");
        }

        long start = System.currentTimeMillis();
        int limit = limit(state.taskArgs());
        ToolResult result = query(state.userId(), enrollmentNo, limit);

        Map<String, Object> args = new LinkedHashMap<>();
        args.put("enrollmentNo", enrollmentNo);
        args.put("limit", limit);

        List<Map<String, Object>> trace = new ArrayList<>(state.taskTrace());
        trace.add(TaskReadResults.traceItem(TaskTools.READ_QUOTA, args,
                "查询报名单关联的额度记录", result, properties));
        List<Map<String, Object>> evidence = new ArrayList<>(state.taskEvidence());
        evidence.addAll(TaskReadResults.evidence(result, properties));
        evidence = limitEvidence(evidence);
        List<Map<String, Object>> data = new ArrayList<>(state.taskData());
        data.addAll(TaskReadResults.dataRows(result));

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.TASK_TRACE, limitTrace(trace));
        updates.put(WorkflowState.TASK_EVIDENCE, evidence);
        updates.put(WorkflowState.TASK_DATA, data);
        updates.put(WorkflowState.ERROR, result.success() ? "" : result.errorMessage());
        log.info("[task-quota-read] done, enrollmentNo={}, success={}, rows={}, evidence={}, "
                        + "errorCode={}, costMs={}",
                enrollmentNo, result.success(), data.size(), evidence.size(), result.errorCode(),
                System.currentTimeMillis() - start);
        return updates;
    }

    /** 按报名单号查询当前用户的额度；领域服务同时按 user_id 隔离数据。 */
    private ToolResult query(String userId, String enrollmentNo, int limit) {
        long start = System.currentTimeMillis();
        try {
            List<Quota> records = quotaService.list(userId, enrollmentNo, null, null, limit);
            return TaskReadResults.quota(records, start);
        } catch (Exception e) {
            log.warn("[task-quota-read] query failed, enrollmentNo={}, reason={}",
                    enrollmentNo, e.getMessage());
            return ToolResult.failure("READ_FAILED", "查询失败: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    private int limit(Map<String, Object> args) {
        Object value = args.get("limit");
        if (value == null) {
            return DEFAULT_LIMIT;
        }
        try {
            return Math.max(1, Math.min(MAX_LIMIT, Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException e) {
            return DEFAULT_LIMIT;
        }
    }

    private List<Map<String, Object>> limitEvidence(List<Map<String, Object>> evidence) {
        int max = properties.getLimits().getTaskEvidenceMaxEntries();
        return evidence.size() <= max ? evidence : new ArrayList<>(evidence.subList(0, max));
    }

    private List<Map<String, Object>> limitTrace(List<Map<String, Object>> trace) {
        int max = properties.getLimits().getTaskTraceMaxEntries();
        return trace.size() <= max ? trace : new ArrayList<>(trace.subList(0, max));
    }

    private Long positiveLong(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long id = Long.parseLong(value.trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        return updates;
    }
}
