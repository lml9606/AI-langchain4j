package com.example.langchain4j.tool.provider;

import com.example.langchain4j.graph.task.TaskTools;
import com.example.langchain4j.graph.task.nodes.TaskReadResults;
import com.example.langchain4j.repository.entity.Enrollment;
import com.example.langchain4j.service.EnrollmentService;
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
 * 只读报名查询工具：供受控 ReAct 诊断分支按报名单号或最近记录查询。
 *
 * 用户身份只来自 ToolContext，工具参数不能覆盖 userId；查询仍由领域服务在 SQL 层
 * 追加 user_id 条件，保证不同用户的数据隔离。
 */
@Component
public class EnrollmentQueryTool implements AgentTool {

    private static final int DEFAULT_LIMIT = 5;
    private static final int MAX_LIMIT = 20;

    private final EnrollmentService enrollmentService;

    public EnrollmentQueryTool(EnrollmentService enrollmentService) {
        this.enrollmentService = enrollmentService;
    }

    /** 工具目录：报名单号可选，limit 由执行器按 Schema 做类型和边界校验。 */
    @Override
    public ToolDefinition definition() {
        return new ToolDefinition(
                TaskTools.READ_ENROLLMENT,
                "查询当前用户的报名记录。可按报名单号精确查询；不传报名单号时返回最近记录。",
                "{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{"
                        + "\"enrollmentNo\":{\"type\":\"string\",\"description\":\"报名单号，可选\"},"
                        + "\"limit\":{\"type\":\"integer\",\"description\":\"返回条数，1-" + MAX_LIMIT
                        + "\",\"default\":" + DEFAULT_LIMIT + ",\"minimum\":1,\"maximum\":" + MAX_LIMIT
                        + "}}}",
                ToolSideEffect.READ_ONLY,
                5000,
                new ToolDefinition.ToolPolicy(true, 20, Set.of()));
    }

    /**
     * 按报名单号精确查询，未提供单号时返回当前用户最近记录。
     * 查询异常交给执行器统一转成失败结果，本方法不伪造空数据。
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
        int limit = limit(args);

        List<Enrollment> records;
        if (enrollmentNo.isBlank()) {
            records = enrollmentService.list(userId, limit);
        } else {
            Enrollment record = enrollmentService.findByNo(userId, enrollmentNo);
            records = record == null ? List.of() : List.of(record);
        }
        return TaskReadResults.enrollment(records, start);
    }

    /** limit 缺省取默认值，并限制在工具声明的最大范围内。 */
    private int limit(JsonNode args) {
        int value = args.path("limit").asInt(DEFAULT_LIMIT);
        return Math.max(1, Math.min(MAX_LIMIT, value));
    }
}
