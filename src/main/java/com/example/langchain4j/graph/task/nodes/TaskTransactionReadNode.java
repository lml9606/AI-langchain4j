package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.business.transaction.QuotaTransaction;
import com.example.langchain4j.business.transaction.QuotaTransactionService;
import com.example.langchain4j.common.constant.QueryLimits;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.model.TaskReadTarget;
import com.example.langchain4j.graph.task.support.TaskReadResults;
import com.example.langchain4j.tool.api.constant.ToolNames;
import com.example.langchain4j.tool.api.model.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.example.langchain4j.common.constant.QueryLimits.DEFAULT_ROW_LIMIT;
import static com.example.langchain4j.common.constant.QueryLimits.MAX_ROW_LIMIT;

/**
 * 第二段只读节点：用报名或额度查询命中的主键直接查询关联交易流水。
 *
 * 只在 withTransactions=true 且第一段已命中的情况下进入；
 * 查询直接调用领域 Service，不再经过 AgentTool 和工具线程池。
 */
@Slf4j
@Component
public class TaskTransactionReadNode implements NodeAction<WorkflowState> {

    private final QuotaTransactionService transactionService;
    private final AgentProperties properties;

    /** 注入交易流水领域服务和证据/轨迹上限配置。 */
    public TaskTransactionReadNode(QuotaTransactionService transactionService,
                                    AgentProperties properties) {
        this.transactionService = transactionService;
        this.properties = properties;
    }

    /**
     * 读取第一段来源关联的交易流水，并把第二段轨迹和证据追加到已有结果。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        TaskReadTarget sourceTarget = TaskReadTarget.parse(state.taskSourceType());
        if (sourceTarget != TaskReadTarget.ENROLLMENT && sourceTarget != TaskReadTarget.QUOTA) {
            return fail("缺少报名或额度来源，无法查询关联交易");
        }
        Long sourceId = positiveLong(state.taskSourceId());
        if (sourceId == null) {
            return fail("报名或额度主键无效，无法查询关联交易");
        }

        long start = System.currentTimeMillis();
        Map<String, Object> args = new LinkedHashMap<>();
        args.put(sourceTarget == TaskReadTarget.ENROLLMENT ? "enrollmentId" : "quotaId", sourceId);
        args.put("limit", limit(state.taskArgs()));
        ToolResult result = query(state.userId(), sourceTarget, sourceId, limit(args));

        List<Map<String, Object>> trace = new ArrayList<>(state.taskTrace());
        trace.add(TaskReadResults.traceItem(ToolNames.QUERY_TRANSACTION, args,
                "查询报名或额度关联的额度交易流水", result, properties));
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
        log.info("[task-transaction-read] done, source={}, sourceId={}, success={}, rows={}, evidence={}, "
                        + "errorCode={}, costMs={}",
                sourceTarget, sourceId, result.success(), data.size(), evidence.size(),
                result.errorCode(),
                System.currentTimeMillis() - start);
        return updates;
    }

    /** 按来源类型选择 quotaId 或 enrollmentId 直查交易服务。 */
    private ToolResult query(String userId, TaskReadTarget sourceTarget, Long sourceId, int limit) {
        long start = System.currentTimeMillis();
        try {
            List<QuotaTransaction> records = sourceTarget == TaskReadTarget.ENROLLMENT
                    ? transactionService.list(userId, null, sourceId, limit)
                    : transactionService.list(userId, sourceId, null, limit);
            return TaskReadResults.transaction(records, start);
        } catch (Exception e) {
            log.warn("[task-transaction-read] query failed, source={}, sourceId={}, reason={}",
                    sourceTarget, sourceId, e.getMessage());
            return ToolResult.failure("READ_FAILED", "查询失败: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    /** 读取并裁剪模型传入的 limit；缺失或非数字时回退默认值。 */
    private int limit(Map<String, Object> args) {
        Object value = args.get("limit");
        if (value == null) {
            return DEFAULT_ROW_LIMIT;
        }
        try {
            return Math.max(1, Math.min(MAX_ROW_LIMIT, Integer.parseInt(String.valueOf(value))));
        } catch (NumberFormatException e) {
            return DEFAULT_ROW_LIMIT;
        }
    }

    /** 按配置截断证据条数，防止第二段查询把状态体积放大。 */
    private List<Map<String, Object>> limitEvidence(List<Map<String, Object>> evidence) {
        int max = properties.getLimits().getTaskEvidenceMaxEntries();
        return evidence.size() <= max ? evidence : new ArrayList<>(evidence.subList(0, max));
    }

    /** 按配置截断轨迹条数，只保留最早的关键步骤。 */
    private List<Map<String, Object>> limitTrace(List<Map<String, Object>> trace) {
        int max = properties.getLimits().getTaskTraceMaxEntries();
        return trace.size() <= max ? trace : new ArrayList<>(trace.subList(0, max));
    }

    /** 解析正整数字符串；非法或非正数返回 null。 */
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

    /** 构造只包含 error 的失败增量，图路由据此进入兜底。 */
    private Map<String, Object> fail(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        return updates;
    }
}
