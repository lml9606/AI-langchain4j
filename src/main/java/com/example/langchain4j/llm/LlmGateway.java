package com.example.langchain4j.llm;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.dto.request.LlmRequest;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.model.chat.request.ResponseFormat;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * LLM 统一门面：负责文本/JSON 输出分流与结构化解析，具体调用/重试/限流/熔断交给 {@link LlmInvoker}。
 * <p>
 * 职责边界：
 * <ul>
 *   <li>LlmGateway：业务调用方看到的稳定 API，只保留 generate / generateJson 两个入口；</li>
 *   <li>LlmRequest：承载模型、消息、输出预算和日志场景，替代分散的方法重载；</li>
 *   <li>LlmInvoker：一次逻辑调用的重试、超时、模型级限流和线程隔离；</li>
 *   <li>LlmCircuitBreaker：按模型维护熔断状态，避免一个模型故障拖垮全部模型。</li>
 * </ul>
 */
@Slf4j
@Service
public class LlmGateway {

    private final LlmInvoker llmInvoker;

    public LlmGateway(LlmInvoker llmInvoker) {
        this.llmInvoker = llmInvoker;
    }

    /** 普通文本生成；输出上限未指定时由 LlmInvoker 使用全局默认值。 */
    public LlmResult generate(LlmRequest request) {
        return llmInvoker.invoke(request.model(), request.messages(), null, request.maxOutputTokens());
    }

    /**
     * JSON 结构化输出：截断感知 + 提取解析一步到位。
     * 返回 null 表示输出不可用（截断或解析失败），由调用方按业务语义降级。
     */
    public JsonNode generateJson(LlmRequest request) {
        long start = System.currentTimeMillis();
        LlmResult result = llmInvoker.invoke(request.model(), request.messages(),
                ResponseFormat.JSON, request.maxOutputTokens());
        if (result.truncated()) {
            log.warn("[{}] llm output truncated, json unavailable, model={}, maxTokens={}, cost={}ms",
                    request.scene(), request.model(), outputLimit(request), System.currentTimeMillis() - start);
            return null;
        }
        try {
            String cleaned = result.text().replaceAll("[\\p{Cntrl}]", " ");
            JsonNode node = JsonUtil.fromJson(JsonUtil.extractJsonObject(cleaned), JsonNode.class);
            log.info("[{}] llm json done, model={}, maxTokens={}, cost={}ms, outputLength={}",
                    request.scene(), request.model(), outputLimit(request), System.currentTimeMillis() - start,
                    result.text().length());
            return node;
        } catch (Exception e) {
            log.warn("[{}] json extract/parse failed, outputLength={}, cost={}ms: {}",
                    request.scene(), result.text().length(), System.currentTimeMillis() - start, e.getMessage());
            return null;
        }
    }

    /** 日志展示：未指定时标记为 default，避免打印 null 造成误解。 */
    private String outputLimit(LlmRequest request) {
        return request.maxOutputTokens() == null ? "default" : String.valueOf(request.maxOutputTokens());
    }
}
