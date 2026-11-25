package com.example.langchain4j.tool.provider;

import com.example.langchain4j.business.quota.Quota;
import com.example.langchain4j.business.quota.QuotaService;
import com.example.langchain4j.common.constant.QueryLimits;
import com.example.langchain4j.graph.task.support.TaskReadResults;
import com.example.langchain4j.tool.api.constant.ToolNames;
import com.example.langchain4j.tool.api.contract.AgentTool;
import com.example.langchain4j.tool.api.model.ToolContext;
import com.example.langchain4j.tool.api.model.ToolDefinition;
import com.example.langchain4j.tool.api.model.ToolResult;
import com.example.langchain4j.tool.api.model.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Set;

import static com.example.langchain4j.common.constant.QueryLimits.DEFAULT_ROW_LIMIT;
import static com.example.langchain4j.common.constant.QueryLimits.MAX_ROW_LIMIT;

/**
 * 只读额度查询工具：供受控 ReAct 诊断分支按报名单号、活动编号或月份查询当前用户额度。
 *
 * 月格式在工具内再次校验，避免模型输出非法条件穿透到数据库；userId 只从执行上下文读取。
 */
@Component
public class QuotaQueryTool implements AgentTool {

    private final QuotaService quotaService;

    /** 注入额度领域服务；用户范围由 ToolContext 传入，不接受模型参数覆盖。 */
    public QuotaQueryTool(QuotaService quotaService) {
        this.quotaService = quotaService;
    }

    /** 声明只读额度工具：支持报名单号、活动编号和月份过滤。 */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                ToolNames.QUERY_QUOTA,
                "查询当前用户的额度记录。支持按报名单号、活动编号和月份过滤；不传条件时返回最近记录。",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"enrollmentNo\":{\"type\":\"string\",\"description\":\"关联报名单号，可选\"},"
                        + "\"activityNo\":{\"type\":\"string\",\"description\":\"活动编号，可选\"},"
                        + "\"month\":{\"type\":\"string\",\"description\":\"额度月份，格式 YYYY-MM\"},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"返回条数，1-" + MAX_ROW_LIMIT
                        + "\",\"default\":" + DEFAULT_ROW_LIMIT + ",\"minimum\":1,\"maximum\":" + MAX_ROW_LIMIT
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
        // 步骤1：用户身份只从执行上下文读取，参数只作为业务过滤条件。
        String userId = context.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResult.failure("UNAUTHORIZED_USER", "缺少用户身份，拒绝查询",
                    System.currentTimeMillis() - start);
        }
        // 步骤2：读取可选过滤条件；空字符串表示不过滤。
        String enrollmentNo = args.path("enrollmentNo").asText("").trim();
        String activityNo = args.path("activityNo").asText("").trim();
        String month = args.path("month").asText("").trim();
        // 月份在工具内再校验一次，防止模型输出非法格式穿透到数据库查询。
        if (!month.isEmpty() && !month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            return ToolResult.failure("INVALID_ARGS", "month 格式必须为 YYYY-MM",
                    System.currentTimeMillis() - start);
        }
        // 步骤3：领域服务按 user_id 做数据隔离，并统一转换成业务行和证据。
        List<Quota> records = quotaService.list(userId, enrollmentNo, activityNo, month, limit(args));
        return TaskReadResults.quota(records, start);
    }

    /** limit 缺省取默认值，并限制在工具声明的最大范围内。 */
    private int limit(JsonNode args) {
        int value = args.path("limit").asInt(DEFAULT_ROW_LIMIT);
        return Math.max(1, Math.min(MAX_ROW_LIMIT, value));
    }
}
