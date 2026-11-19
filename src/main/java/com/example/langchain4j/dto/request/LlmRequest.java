package com.example.langchain4j.dto.request;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;

import java.util.ArrayList;
import java.util.List;

/**
 * LLM 调用请求：统一承载模型、消息、输出预算和日志场景。
 * <p>
 * 设计目的：替代 LlmGateway 上分散的 generate/generateJsonNode 重载。
 * 新增参数只改本对象，不再增加 Gateway 方法；responseFormat 由调用方法决定，
 * 避免出现“请求声明 JSON、却调了文本方法”的非法状态。
 */
public record LlmRequest(String model, List<ChatMessage> messages,
                         Integer maxOutputTokens, String scene) {

    public LlmRequest {
        messages = messages == null ? List.of() : List.copyOf(messages);
        scene = scene == null || scene.isBlank() ? "llm" : scene;
    }

    /** system + user 两段式消息；空 system 不占消息位。 */
    public static LlmRequest of(String model, String systemPrompt, String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>(2);
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.add(SystemMessage.from(systemPrompt));
        }
        messages.add(UserMessage.from(userPrompt == null ? "" : userPrompt));
        return new LlmRequest(model, messages, null, "llm");
    }

    /** 已完成消息列表。 */
    public static LlmRequest of(String model, List<ChatMessage> messages) {
        return new LlmRequest(model, messages, null, "llm");
    }

    /** 指定本次输出 token 上限；不设置时由 LlmInvoker 使用全局默认值。 */
    public LlmRequest maxOutputTokens(int maxOutputTokens) {
        return new LlmRequest(model, messages, maxOutputTokens, scene);
    }

    /** 指定日志/指标场景，便于区分 rewrite、intent-plan、generate 等调用。 */
    public LlmRequest scene(String scene) {
        return new LlmRequest(model, messages, maxOutputTokens, scene);
    }
}
