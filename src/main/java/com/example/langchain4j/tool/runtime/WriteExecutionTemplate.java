package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.service.ToolExecutionService;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * 写工具执行模板：幂等台账与业务写入共用一个事务。
 *
 * 业务工具只关心“怎么改业务数据”，不关心 confirmationId、PENDING/RUNNING、重放或冲突。
 * 所有写工具统一走这里：先 begin 抢到 RUNNING 执行权，再调用业务写入，最后 succeed 或 fail。
 * 业务抛异常时事务整体回滚，台账不会留下“成功但业务没改”的假象。
 */
@Slf4j
@Service
public class WriteExecutionTemplate {

    private final ToolExecutionService executionService;

    public WriteExecutionTemplate(ToolExecutionService executionService) {
        this.executionService = executionService;
    }

    /**
     * 在同一个事务里执行一次写操作。
     *
     * @param context  已确认且带幂等键的工具上下文
     * @param toolName 工具名，用于台账归属与审计
     * @param args     参数，用于生成参数指纹，防止同一幂等键换参数重放
     * @param business 真正的业务写入；返回值决定台账 SUCCEEDED / FAILED；
     *                 抛异常时整个事务回滚，由外层线程池模板归一为工具失败
     */
    @Transactional(rollbackFor = Exception.class)
    public ToolResult execute(ToolContext context, String toolName, JsonNode args,
                              Callable<ToolResult> business) throws Exception {
        long start = System.currentTimeMillis();
        ToolExecution execution = new ToolExecution();
        execution.setExecutionId(context.idempotencyKey());
        execution.setUserId(context.userId());
        execution.setConversationId(context.conversationId());
        execution.setTool(toolName);
        execution.setArgsHash(argsHash(args));
        execution.setArgsJson(args == null ? "{}" : JsonUtil.toJson(args));

        ToolExecutionService.BeginResult begin = executionService.begin(execution);
        switch (begin.status()) {
            case REPLAY -> {
                ToolExecution existing = begin.execution();
                String summary = existing == null || existing.getResult() == null
                        ? "该操作已执行过" : existing.getResult();
                log.info("[write-template] replay, tool={}, executionTail={}", toolName,
                        idTail(context.idempotencyKey()));
                return ToolResult.success(null, summary, List.of(), elapsed(start));
            }
            case IN_PROGRESS -> {
                return ToolResult.failure("IN_PROGRESS", "该操作正在执行中，请稍后查询结果",
                        elapsed(start));
            }
            case CONFLICT -> {
                return ToolResult.failure("IDEMPOTENCY_CONFLICT",
                        "同一确认标识对应了不同的操作参数，已拒绝执行", elapsed(start));
            }
            case ACQUIRED -> {
                // 已取得执行权，继续执行业务写入
            }
            default -> {
                return ToolResult.failure("IDEMPOTENCY_ERROR", "幂等校验失败", elapsed(start));
            }
        }

        ToolResult result = business.call();
        if (result == null) {
            result = ToolResult.failure("EMPTY_RESULT", "工具返回空结果", elapsed(start));
        }
        if (result.success()) {
            executionService.succeed(context.userId(), context.idempotencyKey(),
                    result.summary());
        } else {
            executionService.fail(context.userId(), context.idempotencyKey(),
                    result.errorCode(), result.errorMessage());
        }
        log.info("[write-template] done, tool={}, success={}, executionTail={}", toolName,
                result.success(), idTail(context.idempotencyKey()));
        return result;
    }

    /** 参数指纹：同一幂等键换参数必须被识别出来。 */
    private String argsHash(JsonNode args) {
        String raw = args == null ? "{}" : args.toString();
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(raw.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            return raw;
        }
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
