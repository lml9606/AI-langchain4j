package com.example.langchain4j.llm;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM 网关：所有模型调用的统一入口（图内节点与图外 QueryRewriter/SummaryService 都经此网关）。
 * 提供三层防护：
 * 1. 超时控制（Future.get + timeout，防止模型挂起拖垮线程池）；
 * 2. 指数退避重试（1s/2s/4s + 随机抖动，避免瞬时抖动直接失败）；
 * 3. Redis 计数熔断（Redisson 实现：连续失败达阈值后冷却期内快速失败，保护下游与自身线程池）。
 *
 * Redis 不可用时熔断自动 fail-open（放行调用），保证可用性优先。
 */
@Slf4j
@Service
public class LlmGateway {

    private static final String CB_FAILURES_KEY = "agent:llm:cb:failures";
    private static final String CB_OPEN_KEY = "agent:llm:cb:open";
    private static final long BASE_BACKOFF_MS = 1000L;
    /** 日志用原始输出截断长度：防 LLM 超长输出刷屏 */
    private static final int MAX_LOG_TEXT_LENGTH = 200;

    private final ModelRegistry modelRegistry;
    private final RedissonClient redissonClient;
    private final AgentProperties properties;
    /**
     * LLM 调用执行器：独立于图执行线程池（llmExecutor Bean）。
     * 隔离原因：图执行线程会同步等待本执行器上的 LLM
     * 任务，共用同一池时高并发下
     * 两者相互等待形成准死锁；独立池后等待只阻塞调用线程，不影响其他 LLM 任务执行。
     */
    private final Executor llmExecutor;
    private final AgentMetrics metrics;

    public LlmGateway(ModelRegistry modelRegistry,
                      RedissonClient redissonClient,
                      AgentProperties properties,
                      @Qualifier("llmExecutor") Executor llmExecutor,
                      AgentMetrics metrics) {
        this.modelRegistry = modelRegistry;
        this.redissonClient = redissonClient;
        this.properties = properties;
        this.llmExecutor = llmExecutor;
        this.metrics = metrics;
    }

    /** 便捷方法：system + user 两段式（按模型名路由到对应 ChatModel） */
    public LlmResult generate(String modelName, String systemPrompt, String userPrompt) {
        return generate(modelName, buildMessages(systemPrompt, userPrompt), null);
    }

    /**
     * JSON 模式便捷方法：以 response_format=json_object 请求模型（API 层约束输出为合法 JSON），
     * 供改写器/意图识别等结构化输出场景使用；配合调用方解析层容错，双保险。
     */
    public LlmResult generateJson(String modelName, String systemPrompt, String userPrompt) {
        return generate(modelName, buildMessages(systemPrompt, userPrompt), ResponseFormat.JSON);
    }

    /**
     * JSON 模式 + 自定义输出预算：整份简历改稿等长输出场景使用。
     * 不覆盖全局 {@code agent.llm.max-output-tokens}（短 JSON 节点仍用全局值）。
     *
     * @param maxOutputTokens 本次调用的输出 token 上限
     */
    public LlmResult generateJson(String modelName, String systemPrompt, String userPrompt,
                                  int maxOutputTokens) {
        return generate(modelName, buildMessages(systemPrompt, userPrompt), ResponseFormat.JSON,
                maxOutputTokens);
    }

    /**
     * JSON 结构化输出便捷方法：generateJson + 截断感知 + 提取解析一步到位，
     * 消除各结构化调用点重复的「截断检查 + extractJsonObject + fromJson」样板代码。
     * <p>
     * 返回 null 表示输出不可用（被 maxOutputTokens 截断或提取解析失败，网关已打点告警），
     * 由调用方按各自业务语义 fail-open 降级；LLM 调用失败（BizException）仍向上抛出，
     * 不在此层吞掉，保持「网关感知截断、调用方决策降级」的职责分离。
     *
     * @param modelName    模型名（路由到注册表对应 ChatModel）
     * @param systemPrompt 系统提示词（可为空）
     * @param userPrompt   用户提示词
     * @param scene        场景标识（如 hop-check/decompose），仅用于日志定位
     * @return 解析出的 JSON 节点；截断或提取解析失败返回 null
     */
    public JsonNode generateJsonNode(String modelName, String systemPrompt, String userPrompt, String scene) {
        return generateJsonNode(modelName, systemPrompt, userPrompt, scene,
                properties.getLlm().getMaxOutputTokens());
    }

    /**
     * JSON 结构化输出 + 自定义输出预算：截断感知 + 提取解析一步到位。
     * 供简历优化等需要长输出的结构化场景使用。
     */
    public JsonNode generateJsonNode(String modelName, String systemPrompt, String userPrompt,
                                     String scene, int maxOutputTokens) {
        LlmResult result = generateJson(modelName, systemPrompt, userPrompt, maxOutputTokens);
        // 输出被 maxOutputTokens 截断 → JSON 不完整，返回 null 交给调用方降级（doGenerate 已打点 llm_truncated_total）
        if (result.truncated()) {
            log.warn("[{}] llm output truncated, json unavailable", scene);
            return null;
        }
        try {
            // 提取前控制字符清洗（防裸控制字符破坏 JSON 解析，与调用方字段级清洗规则一致）
            String cleaned = result.text().replaceAll("[\\p{Cntrl}]", " ");
            return JsonUtil.fromJson(JsonUtil.extractJsonObject(cleaned), JsonNode.class);
        } catch (Exception e) {
            log.warn("[{}] json extract/parse failed, raw={}: {}", scene, abbreviate(result.text()), e.getMessage());
            return null;
        }
    }

    /** 日志用文本截断：防 LLM 超长输出刷屏（MAX_LOG_TEXT_LENGTH 上限） */
    private String abbreviate(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_LOG_TEXT_LENGTH ? text : text.substring(0, MAX_LOG_TEXT_LENGTH);
    }

    private List<ChatMessage> buildMessages(String systemPrompt, String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userPrompt == null ? "" : userPrompt));
        return messages;
    }

    public LlmResult generate(String modelName, List<ChatMessage> messages) {
        return generate(modelName, messages, null, null);
    }

    private LlmResult generate(String modelName, List<ChatMessage> messages, ResponseFormat responseFormat) {
        return generate(modelName, messages, responseFormat, null);
    }

    /**
     * 带重试熔断的完整调用。
     * <p>
     * 执行流程：每轮重试前先查熔断状态 → 单次调用（带超时）→
     * 成功则清零失败计数；失败则退避等待后重试；重试耗尽则计一次失败，
     * 达到阈值时打开熔断（冷却期内所有调用直接快速失败，不再触碰模型）。
     *
     * @param responseFormat 非空时以 json_object 模式请求（不支持的供应商会报错，由调用方降级兜底）
     * @param maxOutputTokens 本次调用的输出 token 上限；null 时使用全局 agent.llm.max-output-tokens
     */
    private LlmResult generate(String modelName, List<ChatMessage> messages,
                               ResponseFormat responseFormat, Integer maxOutputTokens) {
        if (messages == null || messages.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "LLM 调用消息不能为空");
        }
        int maxRetries = Math.max(1, properties.getLlm().getMaxRetries());
        Exception lastFailure = null;
        long start = System.currentTimeMillis();

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (isCircuitOpen()) {
                log.warn("[llm] circuit breaker open, fast fail");
                metrics.recordLlmCall(modelName, false, System.currentTimeMillis() - start);
                throw new BizException(ErrorCode.AGENT_LLM_UNAVAILABLE, "大模型服务熔断中，请稍后重试");
            }
            try {
                LlmResult result = doGenerate(modelName, messages, responseFormat, maxOutputTokens);
                onSuccess();
                metrics.recordLlmCall(modelName, true, System.currentTimeMillis() - start);
                return result;
            } catch (BizException e) {
                metrics.recordLlmCall(modelName, false, System.currentTimeMillis() - start);
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("[llm] attempt {}/{} failed, model={}: {}", attempt + 1, maxRetries, modelName, e.getMessage());
                if (attempt < maxRetries - 1) {
                    sleepWithBackoff(attempt);
                }
            }
        }
        onFailure();
        metrics.recordLlmCall(modelName, false, System.currentTimeMillis() - start);
        log.error("[llm] all attempts failed, model={}", modelName, lastFailure);
        throw new BizException(ErrorCode.AGENT_LLM_UNAVAILABLE, "大模型服务暂时不可用", lastFailure);
    }

    /** 单次调用 + 超时控制（按模型名从注册表取 ChatModel；responseFormat 非空时走 ChatRequest 传参） */
    private LlmResult doGenerate(String modelName, List<ChatMessage> messages,
                                 ResponseFormat responseFormat, Integer maxOutputTokens) throws Exception {
        ChatModel chatModel = modelRegistry.chatModel(modelName);
        long timeoutSeconds = Math.max(1, properties.getLlm().getTimeoutSeconds());
        int outputLimit = maxOutputTokens == null
                ? properties.getLlm().getMaxOutputTokens()
                : maxOutputTokens;
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() -> {
            if (responseFormat == null) {
                return chatModel.chat(messages);
            }
            return chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .parameters(ChatRequestParameters.builder()
                            .responseFormat(responseFormat)
                            .maxOutputTokens(outputLimit)
                            .build())
                    .build());
        }, llmExecutor);
        try {
            ChatResponse response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
                throw new IllegalStateException("模型返回空响应");
            }
            Integer tokens = response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : null;
            FinishReason finishReason = response.finishReason();
            // 输出被 maxOutputTokens 截断：结构化 JSON 大概率不完整，打点告警；
            // 不在此处抛错 —— 调用方按 truncated() 走各自 fail-open 降级（解析层与控制层职责分离）
            if (FinishReason.LENGTH.equals(finishReason)) {
                log.warn("[llm] output truncated by maxOutputTokens, model={}", modelName);
                metrics.recordLlmTruncated(modelName);
            }
            return LlmResult.of(response.aiMessage().text(), tokens, finishReason);
        } catch (TimeoutException e) {
            future.cancel(true);
            throw new IllegalStateException("模型调用超时（" + timeoutSeconds + "s）", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            future.cancel(true);
            throw new IllegalStateException("模型调用被中断", e);
        } catch (java.util.concurrent.ExecutionException e) {
            throw new IllegalStateException("模型调用执行失败: " + rootMessage(e), e);
        }
    }

    /**
     * 指数退避：第 N 次重试等待 BASE_BACKOFF_MS * 2^N 毫秒，
     * 即 1s/2s/4s...，再加 0~500ms 随机抖动 ——
     * 避免多线程/多实例同时重试产生“惊群”冲击下游。
     */
    private void sleepWithBackoff(int attempt) {
        long jitter = ThreadLocalRandom.current().nextLong(500);
        long sleepMs = BASE_BACKOFF_MS * (1L << attempt) + jitter;
        try {
            TimeUnit.MILLISECONDS.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---- 熔断器（Redis 计数实现，故障时 fail-open） ----
    //
    // 三态说明（本实现为简化版，无 half-open 状态）：
    //  closed  ：失败计数低于阈值，正常调用；
    //  open    ：失败计数达到阈值 → 写入带 TTL 的 CB_OPEN_KEY，冷却期内 isCircuitOpen()=true 直接快速失败；
    //  冷却后  ：CB_OPEN_KEY 自动过期，熔断器自动回到 closed（下次调用成功即清空失败计数）。
    // 选择 Redis 计数而非本地计数器：多实例部署时失败次数全局共享，熔断判定一致。

    private boolean isCircuitOpen() {
        try {
            return redissonClient.getBucket(CB_OPEN_KEY).isExists();
        } catch (Exception e) {
            log.warn("[llm] circuit breaker redis unavailable, fail-open: {}", e.getMessage());
            return false;
        }
    }

    /** 调用成功：清零连续失败计数（熔断器回到 closed 状态） */
    private void onSuccess() {
        metrics.setCircuitOpen(false);
        try {
            redissonClient.getBucket(CB_FAILURES_KEY).delete();
        } catch (Exception ignored) {
            // fail-open：Redis 异常不影响主流程
        }
    }

    /**
     * 调用失败（重试全部耗尽后触发一次）：失败计数 +1；
     * 达到阈值时写入带冷却 TTL 的“熔断打开”标记并重置计数，
     * 冷却期内所有调用在 isCircuitOpen() 处快速失败。
     */
    private void onFailure() {
        try {
            long failures = redissonClient.getAtomicLong(CB_FAILURES_KEY).incrementAndGet();
            if (failures >= properties.getLlm().getCircuitBreaker().getFailureThreshold()) {
                redissonClient.getBucket(CB_OPEN_KEY).set("1",
                        properties.getLlm().getCircuitBreaker().getCooldownSeconds(), TimeUnit.SECONDS);
                redissonClient.getBucket(CB_FAILURES_KEY).delete();
                metrics.setCircuitOpen(true);
                log.warn("[llm] circuit breaker OPEN, cooldown {}s",
                        properties.getLlm().getCircuitBreaker().getCooldownSeconds());
            }
        } catch (Exception e) {
            log.warn("[llm] circuit breaker redis unavailable, skip counting: {}", e.getMessage());
        }
    }

    private String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        return String.valueOf(cur.getMessage());
    }
}
