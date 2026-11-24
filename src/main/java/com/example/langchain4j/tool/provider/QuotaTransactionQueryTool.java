package com.example.langchain4j.tool.provider;

import com.example.langchain4j.graph.task.TaskTools;
import com.example.langchain4j.graph.task.nodes.TaskReadResults;
import com.example.langchain4j.repository.entity.QuotaTransaction;
import com.example.langchain4j.service.QuotaTransactionService;
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
 * 只读交易流水查询工具：供受控 ReAct 诊断分支按额度 ID 或报名 ID 查询当前用户流水。
 *
 * 参数中的 ID 只用于过滤，不能改变数据归属；查询仍由领域服务追加 user_id 条件。
 */
@Component
public class QuotaTransactionQueryTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final QuotaTransactionService transactionService;

    public QuotaTransactionQueryTool(QuotaTransactionService transactionService) {
        this.transactionService = transactionService;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                TaskTools.READ_TRANSACTION,
                "查询当前用户的额度交易流水。支持按额度 ID 或报名 ID 过滤；不传条件时返回最近记录。",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"quotaId\":{\"type\":\"integer\",\"description\":\"额度 ID，可选\",\"minimum\":1},"
                        + "\"enrollmentId\":{\"type\":\"integer\",\"description\":\"报名 ID，可选\",\"minimum\":1},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"返回条数，1-" + MAX_LIMIT
                        + "\",\"default\":" + DEFAULT_LIMIT + ",\"minimum\":1,\"maximum\":" + MAX_LIMIT
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
        String userId = context.userId();
        if (userId == null || userId.isBlank()) {
            return ToolResult.failure("UNAUTHORIZED_USER", "缺少用户身份，拒绝查询",
                    System.currentTimeMillis() - start);
        }
        Long quotaId = positiveLong(args, "quotaId");
        Long enrollmentId = positiveLong(args, "enrollmentId");
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

    private int limit(JsonNode args) {
        int value = args.path("limit").asInt(DEFAULT_LIMIT);
        return Math.max(1, Math.min(MAX_LIMIT, value));
    }
}
