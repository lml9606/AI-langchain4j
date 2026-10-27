package com.example.langchain4j.llm;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.config.AgentProperties;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
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

    private final ChatModel chatModel;
    private final StringRedisTemplate redisTemplate;
    private final AgentProperties properties;
    private final Executor llmExecutor;

    public LlmGateway(ChatModel chatModel,
                      StringRedisTemplate redisTemplate,
                      AgentProperties properties,
                      Executor agentExecutor) {
        this.chatModel = chatModel;
        this.redisTemplate = redisTemplate;
        this.properties = properties;
        this.llmExecutor = agentExecutor;
    }

    /** 便捷方法：system + user 两段式 */
    public LlmResult generate(String systemPrompt, String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userPrompt == null ? "" : userPrompt));
        return generate(messages);
    }

    /** 带重试熔断的完整调用 */
    public LlmResult generate(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "LLM 调用消息不能为空");
        }
        int maxRetries = Math.max(1, properties.getLlm().getMaxRetries());
        Exception lastFailure = null;

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (isCircuitOpen()) {
                log.warn("[llm] circuit breaker open, fast fail");
                throw new BizException(ErrorCode.AGENT_LLM_UNAVAILABLE, "大模型服务熔断中，请稍后重试");
            }
            try {
                LlmResult result = doGenerate(messages);
                onSuccess();
                return result;
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("[llm] attempt {}/{} failed: {}", attempt + 1, maxRetries, e.getMessage());
                if (attempt < maxRetries - 1) {
                    sleepWithBackoff(attempt);
                }
            }
        }
        onFailure();
        log.error("[llm] all attempts failed", lastFailure);
        throw new BizException(ErrorCode.AGENT_LLM_UNAVAILABLE, "大模型服务暂时不可用", lastFailure);
    }

    /** 单次调用 + 超时控制 */
    private LlmResult doGenerate(List<ChatMessage> messages) throws Exception {
        long timeoutSeconds = Math.max(1, properties.getLlm().getTimeoutSeconds());
        CompletableFuture<ChatResponse> future = CompletableFuture.supplyAsync(
                () -> chatModel.chat(messages), llmExecutor);
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

    private boolean isCircuitOpen() {
        try {
            return Boolean.TRUE.equals(redisTemplate.hasKey(CB_OPEN_KEY));
        } catch (Exception e) {
            log.warn("[llm] circuit breaker redis unavailable, fail-open: {}", e.getMessage());
            return false;
        }
    }

    private void onSuccess() {
        try {
            redisTemplate.delete(CB_FAILURES_KEY);
        } catch (Exception ignored) {
            // fail-open：Redis 异常不影响主流程
        }
    }

    private void onFailure() {
        try {
            Long failures = redisTemplate.opsForValue().increment(CB_FAILURES_KEY);
            if (failures != null && failures >= properties.getLlm().getCircuitBreaker().getFailureThreshold()) {
                redisTemplate.opsForValue().set(CB_OPEN_KEY, "1",
                        Duration.ofSeconds(properties.getLlm().getCircuitBreaker().getCooldownSeconds()));
                redisTemplate.delete(CB_FAILURES_KEY);
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
