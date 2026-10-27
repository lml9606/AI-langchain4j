package com.example.langchain4j.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * DeepSeek 官方 API 装配（OpenAI 兼容协议）。
 * <p>
 * 说明：
 * <ul>
 *   <li>手动装配而非引入 open-ai starter：starter 会自动装配 ChatModel/EmbeddingModel 等一批 Bean，
 *       与 dashscope starter 的同类 Bean 产生歧义注入；手动装配只多一个 deepseekChatModel；</li>
 *   <li>Bean 注册进 ModelRegistry，与 qwenChatModel 一起支持按模型名路由；</li>
 *   <li>apiKey 为空时 Bean 照常创建，首次调用才报错（与 dashscope starter 行为一致，
 *       由 LlmGateway 重试/熔断兜底）。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class DeepSeekConfig {

    @Bean("deepseekChatModel")
    public ChatModel deepseekChatModel(AgentProperties properties) {
        AgentProperties.Deepseek cfg = properties.getDeepseek();
        log.info("[llm] DeepSeek chat model registered: model={}, baseUrl={}",
                cfg.getModelName(), cfg.getBaseUrl());
        return OpenAiChatModel.builder()
                .apiKey(cfg.getApiKey())
                .baseUrl(cfg.getBaseUrl())
                .modelName(cfg.getModelName())
                .build();
    }
}
