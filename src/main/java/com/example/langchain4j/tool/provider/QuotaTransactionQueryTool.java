package com.example.langchain4j.tool.provider;

import com.example.langchain4j.business.transaction.QuotaTransaction;
import com.example.langchain4j.business.transaction.QuotaTransactionService;
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
 * 只读交易流水查询工具：供受控 ReAct 诊断分支按额度 ID 或报名 ID 查询当前用户流水。
 *
 * 参数中的 ID 只用于过滤，不能改变数据归属；查询仍由领域服务追加 user_id 条件。
 */
@Component
public class QuotaTransactionQueryTool implements AgentTool {

    private final QuotaTransactionService transactionService;

    /** 注入交易流水领域服务；工具只做只读查询和结果转换，不修改业务数据。 */
    public QuotaTransactionQueryTool(QuotaTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    /** 声明只读交易工具：支持按额度 ID 或报名 ID 过滤。 */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                ToolNames.QUERY_TRANSACTION,
                "查询当前用户的额度交易流水。支持按额度 ID 或报名 ID 过滤；不传条件时返回最近记录。",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"quotaId\":{\"type\":\"integer\",\"description\":\"额度 ID，可选\",\"minimum\":1},"
                        + "\"enrollmentId\":{\"type\":\"integer\",\"description\":\"报名 ID，可选\",\"minimum\":1},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"返回条数，1-" + MAX_ROW_LIMIT
                        + "\",\"default\":" + DEFAULT_ROW_LIMIT + ",\"minimum\":1,\"maximum\":" + MAX_ROW_LIMIT
                        + "}}}",
                ToolSideEffect.READ_ONLY,
                5000,
                new ToolDefinition.ToolPolicy(true, 20, Set.of()));
    }

    /**
     * 读取交易流水；quotaId 与 enrollmentId 都为空时只返回当前用户最近记录。
     */
    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        // 步骤1：用户身份只从执行上下文读取，ID 参数只用于缩小查询范围。
        String userId = context.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResult.failure("UNAUTHORIZED_USER", "缺少用户身份，拒绝查询",
                    System.currentTimeMillis() - start);
        }
        // 步骤2：解析可选关联主键；缺省表示查询当前用户最近流水。
        Long quotaId = positiveLong(args, "quotaId");
        Long enrollmentId = positiveLong(args, "enrollmentId");
        // 步骤3：领域服务在 SQL 层追加 user_id，并把结果转换成统一业务行和证据。
        List<QuotaTransaction> records = transactionService.list(userId, quotaId, enrollmentId, limit(args));
        return TaskReadResults.transaction(records, start);
    }

    /** 缺省返回 null；Schema 已保证传入值为正整数，这里再兜底一次。 */
    private Long positiveLong(JsonNode args, String field) {
        if (!args.hasNonNull(field)) {
            return null;
        }
        long value = args.path(field).asLong(0L);
        return value > 0 ? value : null;
    }

    /** limit 缺省取默认值，并限制在工具声明的最大范围内。 */
    private int limit(JsonNode args) {
        int value = args.path("limit").asInt(DEFAULT_ROW_LIMIT);
        return Math.max(1, Math.min(MAX_ROW_LIMIT, value));
    }
}
