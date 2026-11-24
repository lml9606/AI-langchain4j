package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.task.TaskOperation;
import com.example.langchain4j.graph.task.TaskReadTarget;
import com.example.langchain4j.graph.task.TaskTools;
import com.example.langchain4j.repository.entity.Enrollment;
import com.example.langchain4j.service.EnrollmentService;
import com.example.langchain4j.service.QuotaService;
import com.example.langchain4j.service.QuotaTransactionService;
import com.example.langchain4j.tool.api.ToolResult;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定只读节点：按 TaskResolveNode 选定的目标表直接调用领域 Service。
 *
 * 这些是内部固定查询，模型不能选择工具，因此不再经过 AgentTool 和工具执行器。
 * 节点仍保留轨迹、证据和来源主键，供回答节点引用和两段式查询使用。
 */
@Slf4j
@Component
public class TaskReadNode implements NodeAction<WorkflowState> {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final EnrollmentService enrollmentService;
    private final QuotaService quotaService;
    private final QuotaTransactionService transactionService;
    private final AgentProperties properties;

    public TaskReadNode(EnrollmentService enrollmentService,
                        QuotaService quotaService,
                        QuotaTransactionService transactionService,
                        AgentProperties properties) {
        this.enrollmentService = enrollmentService;
        this.quotaService = quotaService;
        this.transactionService = transactionService;
        this.properties = properties;
    }

    /**
     * 执行第一段只读查询，返回工具轨迹、证据和来源主键。
     * 查询失败保留失败轨迹；查询无数据不算异常，由回答节点说明没有数据。
     */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        if (TaskOperation.parse(state.taskOperation()) != TaskOperation.READ) {
            return fail("只读节点收到非 READ 操作，已拒绝执行");
        }
        TaskReadTarget target = TaskReadTarget.parse(state.taskReadTarget());
        if (target == null) {
            return fail("只读节点未收到有效的目标表，已拒绝执行");
        }
        if (state.userId() == null || state.userId().isBlank()) {
            return fail("缺少用户身份，拒绝查询");
        }

        long start = System.currentTimeMillis();
        Map<String, Object> args = state.taskArgs();
        ToolResult result = query(target, state.userId(), args);
        List<Map<String, Object>> trace = List.of(TaskReadResults.traceItem(
                toolFor(target), args, purposeFor(target), result, properties));
        List<Map<String, Object>> evidence = TaskReadResults.evidence(result, properties);
        List<Map<String, Object>> data = TaskReadResults.dataRows(result);

        String sourceId = "";
        if ((state.taskIncludeTransactions() || state.taskIncludeQuotas())
                && target != TaskReadTarget.TRANSACTION) {
            sourceId = TaskReadResults.firstRowId(result);
        }

        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.TASK_TRACE, trace);
        updates.put(WorkflowState.TASK_EVIDENCE, evidence);
        updates.put(WorkflowState.TASK_DATA, data);
        updates.put(WorkflowState.TASK_SOURCE_TYPE, sourceId.isBlank() ? "" : target.name());
        updates.put(WorkflowState.TASK_SOURCE_ID, sourceId);
        updates.put(WorkflowState.TASK_CLARIFY, false);
        updates.put(WorkflowState.TASK_CONFIRMATION_REQUIRED, false);
        updates.put(WorkflowState.TASK_PENDING_ACTION, Map.of());
        updates.put(WorkflowState.ERROR, result.success() ? "" : result.errorMessage());
        log.info("[task-read] done, target={}, query={}, success={}, rows={}, evidence={}, sourceId={}, "
                        + "errorCode={}, costMs={}",
                target, toolFor(target), result.success(), data.size(), evidence.size(), sourceId,
                result.errorCode(), System.currentTimeMillis() - start);
        return updates;
    }

    /** 按目标表直接调用对应领域服务，读查询不再经过工具线程池。 */
    private ToolResult query(TaskReadTarget target, String userId, Map<String, Object> args) {
        long start = System.currentTimeMillis();
        try {
            return switch (target) {
                case ENROLLMENT -> queryEnrollment(userId, args, start);
                case QUOTA -> queryQuota(userId, args, start);
                case TRANSACTION -> queryTransaction(userId, args, start);
            };
        } catch (Exception e) {
            log.warn("[task-read] query failed, target={}, reason={}", target, e.getMessage());
            return ToolResult.failure("READ_FAILED", "查询失败: " + e.getMessage(),
                    System.currentTimeMillis() - start);
        }
    }

    /** 报名可按单号精确查，也可返回最近记录。 */
    private ToolResult queryEnrollment(String userId, Map<String, Object> args, long start) {
        String enrollmentNo = text(args, "enrollmentNo");
        List<Enrollment> records;
        if (enrollmentNo.isBlank()) {
            records = enrollmentService.list(userId, limit(args));
        } else {
            Enrollment record = enrollmentService.findByNo(userId, enrollmentNo);
            records = record == null ? List.of() : List.of(record);
        }
        return TaskReadResults.enrollment(records, start);
    }

    /** 额度必须校验月份格式，避免把模型输出的非法条件传给数据库。 */
    private ToolResult queryQuota(String userId, Map<String, Object> args, long start) {
        String month = text(args, "month");
        if (!month.isEmpty() && !month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            return ToolResult.failure("INVALID_ARGS", "month 格式必须为 YYYY-MM",
                    System.currentTimeMillis() - start);
        }
        return TaskReadResults.quota(quotaService.list(userId, text(args, "enrollmentNo"),
                text(args, "activityNo"), month, limit(args)), start);
    }

    /** 交易查询支持按额度 ID 或报名 ID 过滤。 */
    private ToolResult queryTransaction(String userId, Map<String, Object> args, long start) {
        Long quotaId = positiveLong(args, "quotaId");
        Long enrollmentId = positiveLong(args, "enrollmentId");
        if (quotaId == null && hasValue(args, "quotaId")) {
            return ToolResult.failure("INVALID_ARGS", "quotaId 必须是正整数",
                    System.currentTimeMillis() - start);
        }
        if (enrollmentId == null && hasValue(args, "enrollmentId")) {
            return ToolResult.failure("INVALID_ARGS", "enrollmentId 必须是正整数",
                    System.currentTimeMillis() - start);
        }
        return TaskReadResults.transaction(transactionService.list(userId, quotaId, enrollmentId,
                limit(args)), start);
    }

    private String toolFor(TaskReadTarget target) {
        return switch (target) {
            case ENROLLMENT -> TaskTools.READ_ENROLLMENT;
            case QUOTA -> TaskTools.READ_QUOTA;
            case TRANSACTION -> TaskTools.READ_TRANSACTION;
        };
    }

    private String purposeFor(TaskReadTarget target) {
        return switch (target) {
            case ENROLLMENT -> "查询当前用户的报名记录";
            case QUOTA -> "查询当前用户的额度记录";
            case TRANSACTION -> "查询当前用户的额度交易流水";
        };
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

    private String text(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? "" : String.valueOf(value).trim();
    }

    private boolean hasValue(Map<String, Object> args, String key) {
        return args.get(key) != null && !String.valueOf(args.get(key)).isBlank();
    }

    private Long positiveLong(Map<String, Object> args, String key) {
        Object value = args.get(key);
        if (value == null) {
            return null;
        }
        try {
            long id = Long.parseLong(String.valueOf(value).trim());
            return id > 0 ? id : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Map<String, Object> fail(String message) {
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.ERROR, message);
        return updates;
    }
}
