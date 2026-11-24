package com.example.langchain4j.tool.provider;

import com.example.langchain4j.graph.task.TaskTools;
import com.example.langchain4j.graph.task.nodes.TaskReadResults;
import com.example.langchain4j.repository.entity.Quota;
import com.example.langchain4j.service.QuotaService;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

/**
 * 只读额度查询工具：供受控 ReAct 诊断分支按报名单号、活动编号或月份查询当前用户额度。
 *
 * 月格式在工具内再次校验，避免模型输出非法条件穿透到数据库；userId 只从执行上下文读取。
 */
@Component
public class QuotaQueryTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final QuotaService quotaService;

    public QuotaQueryTool(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                TaskTools.READ_QUOTA,
                "查询当前用户的额度记录。支持按报名单号、活动编号和月份过滤；不传条件时返回最近记录。",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"enrollmentNo\":{\"type\":\"string\",\"description\":\"关联报名单号，可选\"},"
                        + "\"activityNo\":{\"type\":\"string\",\"description\":\"活动编号，可选\"},"
                        + "\"month\":{\"type\":\"string\",\"description\":\"额度月份，格式 YYYY-MM\"},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"返回条数，1-" + MAX_LIMIT
                        + "\",\"default\":" + DEFAULT_LIMIT + ",\"minimum\":1,\"maximum\":" + MAX_LIMIT
                        + "}}}",
                ToolSideEffect.READ_ONLY,
                5000,
                new ToolDefinition.ToolPolicy(true, 20, Set.of()));
    }

    /**
     * 按可选条件查询额度。月份格式非法时直接返回失败，让 ReAct 在下一步修正参数。
     */
    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String userId = context.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResult.failure("UNAUTHORIZED_USER", "缺少用户身份，拒绝查询",
                    System.currentTimeMillis() - start);
        }
        String enrollmentNo = args.path("enrollmentNo").asText("").trim();
        String activityNo = args.path("activityNo").asText("").trim();
        String month = args.path("month").asText("").trim();
        if (!month.isEmpty() && !month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            return ToolResult.failure("INVALID_ARGS", "month 格式必须为 YYYY-MM",
                    System.currentTimeMillis() - start);
        }
        List<Quota> records = quotaService.list(userId, enrollmentNo, activityNo, month, limit(args));
        return TaskReadResults.quota(records, start);
    }

    private int limit(JsonNode args) {
        int value = args.path("limit").asInt(DEFAULT_LIMIT);
        return Math.max(1, Math.min(MAX_LIMIT, value));
    }
}
