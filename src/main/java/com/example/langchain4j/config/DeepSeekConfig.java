package com.example.langchain4j.config;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * DeepSeek 官方 API 装配（OpenAI 兼容协议）。
 *
 * 说明：
 *
 * - 手动装配而非引入 open-ai starter：starter 会自动装配 ChatModel/EmbeddingModel 等一批 Bean，
 *       与 dashscope starter 的同类 Bean 产生歧义注入；手动装配只多一个 deepseekChatModels；
 * - 配置驱动多模型：agent.deepseek.model-names 列表每个名字注册一个独立实例（共用 key/baseUrl），
 *       以「模型名 → ChatModel」Map 形式交给 ModelRegistry 汇总，与千问一起支持按模型名路由；
 *       新增模型只改配置，零代码改动；
 * - temperature 统一 0.3：客服知识问答低温求稳，与千问（application.properties）保持一致；
 * - apiKey 为空时 Bean 照常创建，首次调用才报错（与 dashscope starter 行为一致，
 *       由 LlmGateway 重试/熔断兜底）。
 *
 */
@Slf4j
@Configuration
public class DeepSeekConfig {

    @Bean
    public Map<String, ChatModel> deepseekChatModels(AgentProperties properties) {
        AgentProperties.Deepseek cfg = properties.getDeepseek();
        Map<String, ChatModel> models = new LinkedHashMap<>();
        // 循环注册：一个模型名一个独立实例；空名/重复名直接过滤并在启动日志告警（防脏配置）
        for (String name : cfg.getModelNames()) {
            if (name == null || name.isBlank()) {
                log.warn("[llm] DeepSeek blank model name skipped");
                continue;
            }
            if (models.containsKey(name)) {
                log.warn("[llm] DeepSeek duplicate model name skipped: {}", name);
                continue;
            }
            ChatModel model = OpenAiChatModel.builder()
                    .apiKey(cfg.getApiKey())
                    .baseUrl(cfg.getBaseUrl())
                    .modelName(name)
                    .temperature(0.3)
                    .build();
            models.put(name, model);
            log.info("[llm] DeepSeek chat model registered: model={}, baseUrl={}",
                    name, cfg.getBaseUrl());
        }
        return models;
    }
}
