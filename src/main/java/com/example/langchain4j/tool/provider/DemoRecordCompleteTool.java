package com.example.langchain4j.tool.provider;

import com.example.langchain4j.service.DemoRecordService;
import com.example.langchain4j.repository.entity.ToolDemoRecord;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 写工具：把当前用户的一条演示记录标记为完成（需要人工确认 + 幂等键）。
 *
 * <p>生产要点：
 * <ul>
 *   <li>WRITE 工具由执行器强制要求 {@code approved=true} 且幂等键非空；</li>
 *   <li>业务写与执行台账同事务，重复确认不会造成重复修改；</li>
 *   <li>越权记录一律返回"不存在或无权操作"，不泄露存在性。</li>
 * </ul>
 */
@Component
public class DemoRecordCompleteTool implements AgentTool {

    private static final String TOOL_NAME = "complete_demo_record";

    private final DemoRecordService demoRecordService;

    public DemoRecordCompleteTool(DemoRecordService demoRecordService) {
        this.demoRecordService = demoRecordService;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                TOOL_NAME,
                "把当前用户的一条演示任务记录标记为完成（写操作，必须经用户确认后执行）。"
                        + "recordId 与 title 二选一：优先 recordId，也可按标题精确匹配。",
                "{\"type\":\"object\",\"additionalProperties\":false,"
                        + "\"anyOf\":[{\"required\":[\"recordId\"]},{\"required\":[\"title\"]}],"
                        + "\"properties\":{"
                        + "\"recordId\":{\"type\":\"integer\",\"minimum\":1,\"description\":\"要完成的记录 ID\"},"
                        + "\"title\":{\"type\":\"string\",\"minLength\":1,\"description\":\"记录标题（与 recordId 二选一，精确匹配）\"}}}",
                ToolSideEffect.WRITE,
                5000,
                new ToolDefinition.ToolPolicy(true, 10, Set.of()));
    }

    @Override
    public ToolResult execute(ToolContext context, JsonNode args) {
        long start = System.currentTimeMillis();
        String userId = context.userId();
        String executionId = context.idempotencyKey();
        if (userId == null || userId.isBlank()) {
            return ToolResult.failure("UNAUTHORIZED_USER", "缺少用户身份，拒绝执行", elapsed(start));
        }
        if (executionId == null || executionId.isBlank()) {
            return ToolResult.failure("MISSING_IDEMPOTENCY_KEY", "写工具缺少幂等键，拒绝执行", elapsed(start));
        }
        Long recordId = resolveRecordId(userId, args);
        if (recordId == null) {
            return ToolResult.failure("INVALID_ARGS", "recordId 与 title 必须二选一并有效", elapsed(start));
        }

        DemoRecordService.CompleteOutcome outcome = demoRecordService.complete(
                userId, context.conversationId(), executionId, TOOL_NAME, recordId);
        if (!outcome.success()) {
            return ToolResult.failure(outcome.errorCode(), outcome.errorMessage(), elapsed(start));
        }
        return ToolResult.success(
                Map.of("recordId", recordId, "status", "DONE", "replayed", outcome.replayed()),
                outcome.result(), List.of(), elapsed(start));
    }

    /**
     * 定位目标记录：recordId 优先；否则按 title 精确匹配。
     * <p>标题重名时返回 null 拒绝执行，不猜一个，避免改错数据。
     */
    private Long resolveRecordId(String userId, JsonNode args) {
        JsonNode recordIdNode = args == null ? null : args.get("recordId");
        if (recordIdNode != null && !recordIdNode.isNull()) {
            if (!recordIdNode.canConvertToLong() || recordIdNode.asLong() <= 0) {
                return null;
            }
            return recordIdNode.asLong();
        }
        JsonNode titleNode = args == null ? null : args.get("title");
        if (titleNode == null || !titleNode.isTextual() || titleNode.asText().isBlank()) {
            return null;
        }
        List<ToolDemoRecord> matches = demoRecordService.findByTitle(userId, titleNode.asText().trim());
        return matches.size() == 1 ? matches.get(0).getId() : null;
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }
}
