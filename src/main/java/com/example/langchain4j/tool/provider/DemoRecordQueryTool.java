package com.example.langchain4j.tool.provider;

import com.example.langchain4j.repository.entity.ToolDemoRecord;
import com.example.langchain4j.service.DemoRecordService;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolEvidence;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 只读工具：查询当前用户的演示记录。
 *
 * userId 来自执行上下文，不接受模型传入，SQL 层强制隔离；参数做白名单校验与上限裁剪，
 * 避免模型传入任意 status 或超大 limit；无数据时明确返回“没有查询到”，不伪造结果。
 */
@Component
public class DemoRecordQueryTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;
    private static final int MAX_SNIPPET = 200;

    private final DemoRecordService demoRecordService;

    public DemoRecordQueryTool(DemoRecordService demoRecordService) {
        this.demoRecordService = demoRecordService;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                "query_demo_record",
                "查询当前用户的演示任务记录（只读）。支持按状态过滤，默认返回最近 5 条。",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"status\":{\"type\":\"string\",\"enum\":[\"OPEN\",\"DONE\"],\"description\":\"按状态过滤，不传表示全部\"},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"返回条数，1-" + MAX_LIMIT
                        + "\",\"default\":" + DEFAULT_LIMIT + ",\"minimum\":1,\"maximum\":" + MAX_LIMIT
                        + "}}}",
                ToolSideEffect.READ_ONLY,
                5000,
                new ToolDefinition.ToolPolicy(true, 30, Set.of()));
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String userId = context.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResult.failure("UNAUTHORIZED_USER", "缺少用户身份，拒绝查询", elapsed(start));
        }
        String status = args == null ? "" : args.path("status").asText("").trim().toUpperCase();
        if (!status.isEmpty()
                && !ToolDemoRecord.STATUS_OPEN.equals(status)
                && !ToolDemoRecord.STATUS_DONE.equals(status)) {
            return ToolResult.failure("INVALID_ARGS", "status 只能是 OPEN 或 DONE", elapsed(start));
        }
        int limit = args != null && args.has("limit")
                ? args.path("limit").asInt(DEFAULT_LIMIT) : DEFAULT_LIMIT;
        limit = Math.max(1, Math.min(MAX_LIMIT, limit));

        List<ToolDemoRecord> records = demoRecordService.list(userId, status.isEmpty() ? null : status, limit);
        List<Map<String, Object>> rows = new ArrayList<>(records.size());
        List<ToolEvidence> evidence = new ArrayList<>(records.size());
        for (ToolDemoRecord record : records) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("id", record.getId());
            row.put("title", record.getTitle());
            row.put("content", record.getContent());
            row.put("status", record.getStatus());
            row.put("updatedAt", record.getUpdatedAt() == null ? "" : record.getUpdatedAt().toString());
            rows.add(row);
            evidence.add(new ToolEvidence("DEMO_RECORD", String.valueOf(record.getId()),
                    record.getTitle(), truncate(record.getContent(), MAX_SNIPPET), 1.0));
        }
        if (rows.isEmpty()) {
            return ToolResult.success(rows, "没有查询到符合条件的演示记录", evidence, elapsed(start));
        }
        StringBuilder summary = new StringBuilder("查询到 ").append(rows.size()).append(" 条演示记录：");
        for (Map<String, Object> row : rows) {
            summary.append("\n- #").append(row.get("id")).append(" ")
                    .append(row.get("title")).append(" [").append(row.get("status")).append("]");
        }
        return ToolResult.success(rows, summary.toString(), evidence, elapsed(start));
    }

    private String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
