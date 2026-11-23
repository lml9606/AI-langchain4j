package com.example.langchain4j.config;

import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

/**
 * 千问模型注册定义。
 *
 * dashscope starter 自动装配 qwenChatModel Bean，此处包装为「模型名 → ChatModel」Map
 * 交给 ModelRegistry 汇总（与 DeepSeek 多模型 Map 同构，注册表统一扁平化合并）；
 * 模型名取自 agent.llm.qwen-model-name
 * （与 langchain4j.community.dashscope.chat-model.model-name 保持一致）。
 */
@Configuration
public class QwenConfig {

    @Bean
    public Map<String, ChatModel> qwenChatModels(@Qualifier("qwenChatModel") ChatModel qwenChatModel,
                                                 AgentProperties properties) {
        return Map.of(properties.getLlm().getQwenModelName(), qwenChatModel);
    }
}
