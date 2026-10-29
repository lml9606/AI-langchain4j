package com.example.langchain4j.llm;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.config.AgentProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * LLM 网关：图内所有模型调用必须经过此网关。
 * 提供三层防护：
 * 1. 超时控制（Future.get + timeout，防止模型挂起拖垮线程池）；
 * 2. 指数退避重试（1s/2s/4s + 随机抖动，避免瞬时抖动直接失败）；
 * 3. Redis 计数熔断（连续失败达阈值后冷却期内快速失败，保护下游与自身线程池）。
 *
 * Redis 不可用时熔断自动 fail-open（放行调用），保证可用性优先。
 */
@Slf4j
@Service
public class LlmGateway {

    private static final String CB_FAILURES_KEY = "agent:llm:cb:failures";
    private static final String CB_OPEN_KEY = "agent:llm:cb:open";
    private static final long BASE_BACKOFF_MS = 1000L;

    private final ModelRegistry modelRegistry;
    private final StringRedisTemplate redisTemplate;
    private final AgentProperties properties;
    private final Executor llmExecutor;
    private final AgentMetrics metrics;

    public LlmGateway(ModelRegistry modelRegistry,
                      StringRedisTemplate redisTemplate,
                      AgentProperties properties,
                      Executor agentExecutor,
                      AgentMetrics metrics) {
        this.modelRegistry = modelRegistry;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.llmExecutor = agentExecutor;
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

    private List<ChatMessage> buildMessages(String systemPrompt, String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userPrompt == null ? "" : userPrompt));
        return messages;
    }

    public LlmResult generate(String modelName, List<ChatMessage> messages) {
        return generate(modelName, messages, null);
    }

    /**
     * 带重试熔断的完整调用。
     * <p>
     * 执行流程：每轮重试前先查熔断状态 → 单次调用（带超时）→
     * 成功则清零失败计数；失败则退避等待后重试；重试耗尽则计一次失败，
     * 达到阈值时打开熔断（冷却期内所有调用直接快速失败，不再触碰模型）。
     *
     * @param responseFormat 非空时以 json_object 模式请求（不支持的供应商会报错，由调用方降级兜底）
     */
    private LlmResult generate(String modelName, List<ChatMessage> messages, ResponseFormat responseFormat) {
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
                LlmResult result = doGenerate(modelName, messages, responseFormat);
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
    private LlmResult doGenerate(String modelName, List<ChatMessage> messages, ResponseFormat responseFormat) throws Exception {
        ChatModel chatModel = modelRegistry.chatModel(modelName);
        long timeoutSeconds = Math.max(1, properties.getLlm().getTimeoutSeconds());
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(() -> {
            if (responseFormat == null) {
                return chatModel.chat(messages);
            }
            return chatModel.chat(ChatRequest.builder()
                    .messages(messages)
                    .parameters(ChatRequestParameters.builder()
                            .responseFormat(responseFormat)
                            .build())
                    .build());
        }, llmExecutor);
        try {
            ChatResponse response = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
                throw new IllegalStateException("模型返回空响应");
            }
            Integer tokens = response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : null;
            return LlmResult.of(response.aiMessage().text(), tokens);
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
            return Boolean.TRUE.equals(redisTemplate.hasKey(CB_OPEN_KEY));
        } catch (Exception e) {
            log.warn("[llm] circuit breaker redis unavailable, fail-open: {}", e.getMessage());
            return false;
        }
    }

    /** 调用成功：清零连续失败计数（熔断器回到 closed 状态） */
    private void onSuccess() {
        metrics.setCircuitOpen(false);
        try {
            redisTemplate.delete(CB_FAILURES_KEY);
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
            Long failures = redisTemplate.opsForValue().increment(CB_FAILURES_KEY);
            if (failures != null && failures >= properties.getLlm().getCircuitBreaker().getFailureThreshold()) {
                redisTemplate.opsForValue().set(CB_OPEN_KEY, "1",
                        Duration.ofSeconds(properties.getLlm().getCircuitBreaker().getCooldownSeconds()));
                redisTemplate.delete(CB_FAILURES_KEY);
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
