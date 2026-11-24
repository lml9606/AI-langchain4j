package com.example.langchain4j.graph.task.nodes;

import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.Enrollment;
import com.example.langchain4j.repository.entity.Quota;
import com.example.langchain4j.repository.entity.QuotaTransaction;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定只读链路的结果转换工具：把领域实体转换成节点轨迹和引用证据。
 *
 * 只负责格式转换，不访问数据库，也不做权限或业务判断。
 */
final class TaskReadResults {

    private static final int MAX_SNIPPET = 200;

    private TaskReadResults() {
    }

    /** 报名记录转统一查询结果。 */
    static ToolResult enrollment(List<Enrollment> records, long start) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ToolEvidence> evidence = new ArrayList<>();
        for (Enrollment record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", record.getId());
            row.put("enrollmentNo", record.getEnrollmentNo());
            row.put("applicantName", record.getApplicantName());
            row.put("phone", maskPhone(record.getPhone()));
            row.put("amount", amount(record.getAmount()));
            row.put("remark", record.getRemark());
            row.put("createdAt", time(record.getCreatedAt()));
            rows.add(row);
            evidence.add(new ToolEvidence("ENROLLMENT", String.valueOf(record.getId()),
                    "报名 " + record.getEnrollmentNo(),
                    truncate("报名人=" + record.getApplicantName() + "，金额="
                            + amount(record.getAmount()) + "，时间=" + time(record.getCreatedAt()),
                            MAX_SNIPPET), 1.0));
        }
        if (rows.isEmpty()) {
            return ToolResult.success(rows, "没有查询到符合条件的报名记录", evidence, elapsed(start));
        }
        StringBuilder summary = new StringBuilder("查询到 ").append(rows.size()).append(" 条报名记录：");
        for (Map<String, Object> row : rows) {
            summary.append("\n- #").append(row.get("id")).append(" ")
                    .append(row.get("enrollmentNo")).append(" 报名人=")
                    .append(row.get("applicantName")).append(" 金额=").append(row.get("amount"));
        }
        return ToolResult.success(rows, summary.toString(), evidence, elapsed(start));
    }

    /** 额度记录转统一查询结果。 */
    static ToolResult quota(List<Quota> records, long start) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ToolEvidence> evidence = new ArrayList<>();
        for (Quota quota : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", quota.getId());
            row.put("activityNo", quota.getActivityNo());
            row.put("enrollmentNo", quota.getEnrollmentNo());
            row.put("month", quota.getMonth());
            row.put("quotaType", quota.getQuotaType());
            row.put("totalAmount", amount(quota.getTotalAmount()));
            row.put("usedAmount", amount(quota.getUsedAmount()));
            row.put("availableAmount", amount(quota.getAvailableAmount()));
            rows.add(row);
            evidence.add(new ToolEvidence("QUOTA", String.valueOf(quota.getId()),
                    "额度 " + quota.getActivityNo() + " " + quota.getMonth()
                            + (quota.getEnrollmentNo() == null || quota.getEnrollmentNo().isBlank()
                            ? "" : " 报名=" + quota.getEnrollmentNo()),
                    truncate("总额度=" + amount(quota.getTotalAmount()) + "，已用="
                            + amount(quota.getUsedAmount()) + "，可用="
                            + amount(quota.getAvailableAmount()), MAX_SNIPPET), 1.0));
        }
        if (rows.isEmpty()) {
            return ToolResult.success(rows, "没有查询到符合条件的额度记录", evidence, elapsed(start));
        }
        StringBuilder summary = new StringBuilder("查询到 ").append(rows.size()).append(" 条额度记录：");
        for (Map<String, Object> row : rows) {
            summary.append("\n- #").append(row.get("id")).append(" 活动=")
                    .append(row.get("activityNo")).append(" 月份=").append(row.get("month"))
                    .append(" 总额=").append(row.get("totalAmount")).append(" 已用=")
                    .append(row.get("usedAmount")).append(" 可用=").append(row.get("availableAmount"));
            Object enrollmentNo = row.get("enrollmentNo");
            if (enrollmentNo != null && !String.valueOf(enrollmentNo).isBlank()) {
                summary.append(" 报名单号=").append(enrollmentNo);
            }
        }
        return ToolResult.success(rows, summary.toString(), evidence, elapsed(start));
    }

    /** 交易流水转统一查询结果。 */
    static ToolResult transaction(List<QuotaTransaction> records, long start) {
        List<Map<String, Object>> rows = new ArrayList<>();
        List<ToolEvidence> evidence = new ArrayList<>();
        for (QuotaTransaction transaction : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", transaction.getId());
            row.put("transactionNo", transaction.getTransactionNo());
            row.put("quotaId", transaction.getQuotaId());
            row.put("enrollmentId", transaction.getEnrollmentId());
            row.put("transactionType", transaction.getTransactionType());
            row.put("amount", amount(transaction.getAmount()));
            row.put("remark", transaction.getRemark());
            row.put("createdAt", time(transaction.getCreatedAt()));
            rows.add(row);
            evidence.add(new ToolEvidence("QUOTA_TRANSACTION", String.valueOf(transaction.getId()),
                    "交易 " + transaction.getTransactionNo(),
                    truncate("类型=" + transaction.getTransactionType() + "，金额="
                            + amount(transaction.getAmount()) + "，时间="
                            + time(transaction.getCreatedAt()), MAX_SNIPPET), 1.0));
        }
        if (rows.isEmpty()) {
            return ToolResult.success(rows, "没有查询到符合条件的交易流水", evidence, elapsed(start));
        }
        StringBuilder summary = new StringBuilder("查询到 ").append(rows.size()).append(" 条交易流水：");
        for (Map<String, Object> row : rows) {
            summary.append("\n- #").append(row.get("id")).append(" ")
                    .append(row.get("transactionNo")).append(" 类型=")
                    .append(row.get("transactionType")).append(" 金额=").append(row.get("amount"));
        }
        return ToolResult.success(rows, summary.toString(), evidence, elapsed(start));
    }

    /** 构造单条节点轨迹，摘要按配置截断。 */
    static Map<String, Object> traceItem(String tool, Map<String, Object> args, String purpose,
                                         ToolResult result, AgentProperties properties) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("tool", tool);
        item.put("args", args);
        item.put("purpose", purpose);
        item.put("success", result.success());
        item.put("summary", Texts.truncate(result.summary(),
                properties.getLimits().getTaskSummaryMaxLength()));
        item.put("errorCode", result.errorCode());
        item.put("costMs", result.costMs());
        return item;
    }

    /** 只保留成功结果中的证据，并按配置限制条数和片段长度。 */
    static List<Map<String, Object>> evidence(ToolResult result, AgentProperties properties) {
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

    /** 提取查询结果的完整业务行，只接受 Map 行并统一字符串键；失败结果返回空列表。 */
    static List<Map<String, Object>> dataRows(ToolResult result) {
        if (!result.success() || !(result.data() instanceof List<?> rows)) {
            return List.of();
        }
        List<Map<String, Object>> data = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                Map<String, Object> item = new LinkedHashMap<>(map.size());
                map.forEach((key, value) -> item.put(String.valueOf(key), value));
                data.add(item);
            }
        }
        return data;
    }

    /** 从查询结果第一行取业务主键，供第二段关联查询使用。 */
    static String firstRowId(ToolResult result) {
        if (!result.success() || !(result.data() instanceof List<?> rows)) {
            return "";
        }
        for (Object row : rows) {
            if (row instanceof Map<?, ?> map) {
                Object id = map.get("id");
                return id == null ? "" : String.valueOf(id);
            }
        }
        return "";
    }

    private static String amount(BigDecimal value) {
        return value == null ? "0" : value.toPlainString();
    }

    private static String time(OffsetDateTime value) {
        return value == null ? "" : value.toString();
    }

    private static String maskPhone(String phone) {
        if (phone == null || phone.isBlank()) {
            return "";
        }
        if (phone.length() <= 7) {
            return "***";
        }
        return phone.substring(0, 3) + "****" + phone.substring(phone.length() - 4);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max) + "...";
    }

    private static long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
