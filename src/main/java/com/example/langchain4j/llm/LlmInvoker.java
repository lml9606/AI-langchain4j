package com.example.langchain4j.llm;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.concurrent.BoundedCall;
import com.example.langchain4j.common.metrics.AgentMetrics;
import com.example.langchain4j.common.util.Throwables;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.guard.RateLimiter;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.FinishReason;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * LLM 单次逻辑调用执行器：模型路由 → 熔断检查 → 模型级限流 → 超时调用 → 重试 → 熔断计数。
 *
 * 限流维度是“模型”而不是“用户”：入口限流保护服务，模型级限流保护上游配额，
 * 两者职责不同，不能互相替代。
 */
@Slf4j
@Component
public class LlmInvoker {

    private final ModelRegistry modelRegistry;
    private final Executor llmExecutor;
    private final RateLimiter rateLimiter;
    private final LlmCircuitBreaker circuitBreaker;
    private final AgentProperties properties;
    private final AgentMetrics metrics;

    public LlmInvoker(ModelRegistry modelRegistry,
                      @Qualifier("llmExecutor") Executor llmExecutor,
                      RateLimiter rateLimiter,
                      LlmCircuitBreaker circuitBreaker,
                      AgentProperties properties,
                      AgentMetrics metrics) {
        this.modelRegistry = modelRegistry;
        this.llmExecutor = llmExecutor;
        this.rateLimiter = rateLimiter;
        this.circuitBreaker = circuitBreaker;
        this.properties = properties;
        this.metrics = metrics;
    }

    /**
     * 执行一次带重试与防护的 LLM 调用。
     *
     * @param maxOutputTokens 本次输出 token 上限；null 时使用全局上限
     */
    public LlmResult invoke(String modelName, List<ChatMessage> messages,
                            ResponseFormat responseFormat, Integer maxOutputTokens) {
        if (messages == null || messages.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "LLM 调用消息不能为空");
        }
        int maxRetries = Math.max(1, properties.getLlm().getMaxRetries());
        Exception lastFailure = null;
        long start = System.currentTimeMillis();

        for (int attempt = 0; attempt < maxRetries; attempt++) {
            if (circuitBreaker.isOpen(modelName)) {
                metrics.recordLlmCall(modelName, false, System.currentTimeMillis() - start);
                throw new BizException(ErrorCode.AGENT_LLM_UNAVAILABLE, "大模型服务熔断中，请稍后重试");
            }
            if (!allowModelCall(modelName)) {
                metrics.recordLlmCall(modelName, false, System.currentTimeMillis() - start);
                throw new BizException(ErrorCode.AGENT_RATE_LIMITED, "模型调用过于频繁，请稍后重试");
            }
            try {
                LlmResult result = invokeOnce(modelName, messages, responseFormat, maxOutputTokens);
                circuitBreaker.onSuccess(modelName);
                metrics.recordLlmCall(modelName, true, System.currentTimeMillis() - start);
                return result;
            } catch (BizException e) {
                metrics.recordLlmCall(modelName, false, System.currentTimeMillis() - start);
                throw e;
            } catch (Exception e) {
                lastFailure = e;
                log.warn("[llm] attempt {}/{} failed, model={}: {}", attempt + 1, maxRetries,
                        modelName, e.getMessage());
                if (attempt < maxRetries - 1) {
                    sleepWithBackoff(attempt);
                }
            }
        }
        circuitBreaker.onFailure(modelName);
        metrics.recordLlmCall(modelName, false, System.currentTimeMillis() - start);
        log.error("[llm] all attempts failed, model={}", modelName, lastFailure);
        throw new BizException(ErrorCode.AGENT_LLM_UNAVAILABLE, "大模型服务暂时不可用", lastFailure);
    }

    /** 模型级固定窗口限流：多实例共享计数，只限制真正发往模型的调用。 */
    private boolean allowModelCall(String modelName) {
        return rateLimiter.tryAcquire("llm:" + modelName, properties.getLlm().getMaxCallsPerMinute());
    }

    /**
     * 单次模型调用：所有请求统一设置输出上限，避免某些调用绕过全局 token 防护。
     *
     * 超时、取消和异常归一复用 {@link BoundedCall}；这里只负责把通用结果翻译成
     * 模型调用语义，不再重复实现 FutureTask/get/cancel 模板。
     */
    private LlmResult invokeOnce(String modelName, List<ChatMessage> messages,
                                 ResponseFormat responseFormat, Integer maxOutputTokens) throws Exception {
        ChatModel chatModel = modelRegistry.chatModel(modelName);
        long timeoutSeconds = Math.max(1, properties.getLlm().getTimeoutSeconds());
        ChatRequest request = buildRequest(messages, responseFormat, resolveOutputLimit(maxOutputTokens));
        BoundedCall.Outcome<ChatResponse> outcome = BoundedCall.execute(
                () -> chatModel.chat(request), llmExecutor, timeoutSeconds * 1000L);
        return switch (outcome.status()) {
            case SUCCESS -> toResult(modelName, outcome.value());
            case TIMEOUT -> throw new IllegalStateException(
                    "模型调用超时（" + timeoutSeconds + "s）", outcome.error());
            case INTERRUPTED -> throw new IllegalStateException("模型调用被中断", outcome.error());
            case REJECTED -> throw new IllegalStateException("模型调用线程池已满", outcome.error());
            case FAILED -> throw new IllegalStateException(
                    "模型调用执行失败: " + Throwables.rootMessage(outcome.error()), outcome.error());
        };
    }

    /** 响应解包：空响应、token 统计和截断指标统一在这里处理。 */
    private LlmResult toResult(String modelName, ChatResponse response) {
        if (response == null || response.aiMessage() == null || response.aiMessage().text() == null) {
            throw new IllegalStateException("模型返回空响应");
        }
        Integer tokens = response.tokenUsage() != null ? response.tokenUsage().totalTokenCount() : null;
        Integer inputTokens = response.tokenUsage() != null ? response.tokenUsage().inputTokenCount() : null;
        Integer outputTokens = response.tokenUsage() != null ? response.tokenUsage().outputTokenCount() : null;
        FinishReason finishReason = response.finishReason();
        if (FinishReason.LENGTH.equals(finishReason)) {
            log.warn("[llm] output truncated by maxOutputTokens, model={}", modelName);
            metrics.recordLlmTruncated(modelName);
        }
        return LlmResult.of(response.aiMessage().text(), tokens, finishReason, inputTokens, outputTokens);
    }

    /** 构建统一 ChatRequest：无论是否 JSON、是否显式传预算，都带 maxOutputTokens。 */
    private ChatRequest buildRequest(List<ChatMessage> messages, ResponseFormat responseFormat, int outputLimit) {
        var parameters = ChatRequestParameters.builder().maxOutputTokens(outputLimit);
        if (responseFormat != null) {
            parameters.responseFormat(responseFormat);
        }
        return ChatRequest.builder().messages(messages).parameters(parameters.build()).build();
    }

    /** 输出上限归一：null/非正数回退全局上限。 */
    private int resolveOutputLimit(Integer maxOutputTokens) {
        if (maxOutputTokens != null && maxOutputTokens > 0) {
            return maxOutputTokens;
        }
        return Math.max(1, properties.getLlm().getMaxOutputTokens());
    }

    /** 指数退避 + 抖动，避免多实例同时重试。 */
    private void sleepWithBackoff(int attempt) {
        long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, properties.getLlm().getBackoffJitterMs()));
        long sleepMs = properties.getLlm().getBackoffBaseMs() * (1L << attempt) + jitter;
        try {
            TimeUnit.MILLISECONDS.sleep(sleepMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

}
