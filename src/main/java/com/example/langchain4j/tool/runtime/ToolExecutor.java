package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.auth.security.UserContext;
import com.example.langchain4j.common.concurrent.BoundedCall;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.common.util.Throwables;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.guard.RateLimiter;
import com.example.langchain4j.tool.api.constant.ToolErrorCode;
import com.example.langchain4j.tool.api.model.ToolContext;
import com.example.langchain4j.tool.api.model.ToolDefinition;
import com.example.langchain4j.tool.api.model.ToolResult;
import com.example.langchain4j.tool.api.model.ToolSideEffect;
import com.example.langchain4j.tool.execution.ToolExecutionRecorder;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.concurrent.Callable;

/**
 * 工具执行器：权限、写契约、参数、限流、隔离线程池和超时统一收口。
 *
 * 任何异常都转成结构化 ToolResult，不向图抛裸异常。工具定义是策略事实源，
 * 执行器只负责在执行前再次做防御性校验，不能信任模型生成的计划。
 *
 * 任务解析节点只负责判断读写和抽取参数，它产出的工具名、参数和确认状态都不可信；
 * 真正产生副作用的是本类，所以权限、写开关、幂等键和参数 Schema 必须在最终执行前再验一次。
 * 确认创建和执行两个阶段共享的是 {@link ToolAccessPolicy} 和 {@link ToolInputValidator}
 * 的判定语义，不是把校验从图里删掉。
 */
@Slf4j
@Service
public class ToolExecutor {

    private final ToolRegistry registry;
    private final ToolAccessPolicy accessPolicy;
    private final ThreadPoolTaskExecutor executor;
    private final AgentProperties properties;
    private final RateLimiter rateLimiter;
    private final WriteExecutionTemplate writeExecutionTemplate;
    private final ToolExecutionRecorder recorder;

    public ToolExecutor(ToolRegistry registry,
                        ToolAccessPolicy accessPolicy,
                        AgentMetrics metrics,
                        @Qualifier("toolExecutorPool") ThreadPoolTaskExecutor executor,
                        AgentProperties properties,
                        RateLimiter rateLimiter,
                        WriteExecutionTemplate writeExecutionTemplate) {
        this.registry = registry;
        this.accessPolicy = accessPolicy;
        this.executor = executor;
        this.properties = properties;
        this.rateLimiter = rateLimiter;
        this.writeExecutionTemplate = writeExecutionTemplate;
        this.recorder = new ToolExecutionRecorder(metrics);
    }

    /**
     * 执行一次工具调用，返回结构化结果；所有失败分支都通过 Outcome 翻译成 ToolResult。
     *
     * 固定顺序是安全语义：解析注册项、权限门禁、写契约、参数 Schema、限流、隔离线程池执行、
     * 记录指标和日志。顺序不能调整，否则模型生成的计划可能绕过最终校验。写工具缺少确认或
     * 幂等键时直接拒绝；线程池满、超时和工具异常都会转成对应的 ToolErrorCode。
     *
     * @param context  用户、会话、确认状态和幂等键
     * @param toolName 工具名
     * @param args     模型生成的参数；null 会被归一为空对象
     * @return 工具结果；成功与失败都以 ToolResult 返回，不向调用方抛裸异常
     */
    public ToolResult invoke(ToolContext context, String toolName, JsonNode args) {
        long start = System.currentTimeMillis();
        JsonNode safeArgs = normalizeArgs(args);

        // 第一步：一次查找同时拿到实现和定义；找不到即未知/停用，不再做第二次回退查找。
        ToolRegistry.RegisteredTool registered = registry.findRegistered(toolName).orElse(null);
        if (registered == null) {
            return fail(context, toolName, start, ToolErrorCode.UNKNOWN_TOOL,
                    "未知或未启用的工具: " + toolName);
        }
        ToolDefinition definition = registered.definition();

        // 第二步：权限门禁。传定义而不是工具名，避免策略层再次查注册表导致策略漂移。
        ToolAccessPolicy.Decision access = accessPolicy.check(definition, UserContext.role());
        if (!access.allowed()) {
            return fail(context, toolName, start,
                    accessErrorCode(access.status()), access.message());
        }

        // 第三步：写工具契约。只读工具直接放行；写工具必须已确认且带幂等键。
        ToolResult writeContractFailure = checkWriteContract(context, definition, toolName, start);
        if (writeContractFailure != null) {
            return record(context, toolName, writeContractFailure, start);
        }

        // 第四步：参数 Schema 校验。工具内部仍可做业务校验，但模型输出不能绕过这一层。
        ToolInputValidator.Result validation = ToolInputValidator.validate(definition, safeArgs);
        if (!validation.valid()) {
            return fail(context, toolName, start, ToolErrorCode.INVALID_ARGS,
                    validation.message());
        }

        // 第五步：限流放在真正执行之前，避免队列满或超时后仍然打满下游。
        if (!allowCall(context, definition, toolName)) {
            return fail(context, toolName, start, ToolErrorCode.RATE_LIMITED,
                    "工具调用过于频繁，请稍后重试");
        }

        // 第六步：隔离线程池 + 超时取消；异常由 BoundedCall 归一，不向图抛裸异常。
        int timeoutMs = definition.timeoutMs() > 0
                ? definition.timeoutMs() : properties.getTool().getTimeoutMs();
        Callable<ToolResult> call = () -> registered.tool().execute(context, safeArgs);
        BoundedCall.Outcome<ToolResult> outcome = definition.sideEffect() == ToolSideEffect.READ_ONLY
                ? BoundedCall.execute(call, executor, timeoutMs)
                : BoundedCall.execute(
                        () -> writeExecutionTemplate.execute(context, toolName, safeArgs, call),
                        executor, timeoutMs);
        ToolResult result = toToolResult(outcome, toolName, timeoutMs, start);

        // 第七步：统一记录。record 同时处理指标和日志，任何失败路径都不会漏记。
        return record(context, toolName, result, start);
    }

    /** 参数归一：null 统一为空对象，避免各工具重复判空。 */
    private JsonNode normalizeArgs(JsonNode args) {
        return args == null || args.isNull() ? JsonUtil.mapper().createObjectNode() : args;
    }

    /**
     * 写工具契约校验：返回 null 表示通过，返回 ToolResult 表示拒绝。
     *
     * 幂等键不是可选优化：图超时、用户重复点击、网络重试都会再次进入执行器，
     * 没有幂等键就无法保证写操作只落库一次。
     */
    private ToolResult checkWriteContract(ToolContext context, ToolDefinition definition,
                                          String toolName, long start) {
        if (definition.sideEffect() == ToolSideEffect.READ_ONLY) {
            return null;
        }
        if (!context.approved()) {
            return ToolResult.failure(ToolErrorCode.CONFIRMATION_REQUIRED.name(),
                    "写工具需要用户确认后执行: " + toolName, elapsed(start));
        }
        if (context.idempotencyKey().isBlank()) {
            return ToolResult.failure(ToolErrorCode.MISSING_IDEMPOTENCY_KEY.name(),
                    "写工具缺少幂等键，拒绝执行: " + toolName, elapsed(start));
        }
        return null;
    }

    /**
     * 工具级限流：用户 + 工具维度，单工具配置优先于全局默认值。
     *
     * 限流维度是“调用尝试次数”，不是“成功执行次数”；队列满或超时也会消耗额度，
     * 这是有意的，避免异常流量绕过配额。
     */
    private boolean allowCall(ToolContext context, ToolDefinition definition, String toolName) {
        String userId = context.userId().isBlank() ? "anonymous" : context.userId();
        int maxCalls = definition.policy().maxCallsPerMinute() > 0
                ? definition.policy().maxCallsPerMinute()
                : properties.getTool().getMaxCallsPerMinute();
        return rateLimiter.tryAcquire("tool:" + userId + ":" + toolName, maxCalls);
    }

    /** 把共享执行模板的结果翻译成工具结果；工具语义只在这里出现。 */
    private ToolResult toToolResult(BoundedCall.Outcome<ToolResult> outcome, String toolName,
                                    int timeoutMs, long start) {
        return switch (outcome.status()) {
            case SUCCESS -> {
                ToolResult result = outcome.value();
                yield result == null
                        ? ToolResult.failure(ToolErrorCode.EMPTY_RESULT.name(),
                        "工具返回空结果", elapsed(start))
                        : result;
            }
            case TIMEOUT -> {
                log.warn("[tool] timeout, name={}, timeoutMs={}, cancelled={}",
                        toolName, timeoutMs, outcome.cancelled());
                yield ToolResult.failure(ToolErrorCode.TIMEOUT.name(),
                        "工具执行超时: " + timeoutMs + "ms", elapsed(start));
            }
            case REJECTED -> ToolResult.failure(ToolErrorCode.OVERLOADED.name(),
                    "工具执行队列已满，请稍后重试", elapsed(start));
            case INTERRUPTED -> ToolResult.failure(ToolErrorCode.INTERRUPTED.name(),
                    "工具执行被中断", elapsed(start));
            case FAILED -> ToolResult.failure(ToolErrorCode.FAILED.name(),
                    Throwables.rootMessage(outcome.error()), elapsed(start));
        };
    }

    /** 记录并返回；抽成方法后所有失败分支只需一行，避免漏掉指标和日志。 */
    private ToolResult record(ToolContext context, String toolName, ToolResult result, long start) {
        return recorder.record(context, toolName, result, start);
    }

    /** 失败快捷方法：统一耗时计算和记录，避免每个分支重复写 System.currentTimeMillis()。 */
    private ToolResult fail(ToolContext context, String toolName, long start,
                            ToolErrorCode code, String message) {
        return fail(context, toolName, start, code.name(), message);
    }

    /** 字符串错误码重载：访问策略状态映射后的结果已经是字符串，避免再做一次枚举转换。 */
    private ToolResult fail(ToolContext context, String toolName, long start,
                            String code, String message) {
        return record(context, toolName,
                ToolResult.failure(code, message, elapsed(start)), start);
    }

    private long elapsed(long start) {
        return System.currentTimeMillis() - start;
    }

    /** 访问策略状态映射到稳定的工具错误码，供任务观察与前端判断使用。 */
    private String accessErrorCode(ToolAccessPolicy.Status status) {
        return switch (status) {
            case NOT_FOUND -> ToolErrorCode.UNKNOWN_TOOL.name();
            case WRITE_DISABLED -> ToolErrorCode.FORBIDDEN_WRITE.name();
            case PERMISSION_REQUIRED, DANGEROUS -> ToolErrorCode.FORBIDDEN_TOOL.name();
            case ALLOWED -> "";
        };
    }
}
