package com.example.langchain4j.config;

import com.example.langchain4j.llm.ModelDefinition;
import dev.langchain4j.model.chat.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 千问模型注册定义。
 * <p>
 * dashscope starter 自动装配 qwenChatModel Bean，此处仅包装为 {@link ModelDefinition}
 * 交给 ModelRegistry 汇总；模型名取自 agent.llm.qwen-model-name
 * （与 langchain4j.community.dashscope.chat-model.model-name 保持一致）。
 */
@Configuration
public class QwenConfig {

    @Bean
    public ModelDefinition qwenModelDefinition(@Qualifier("qwenChatModel") ChatModel qwenChatModel,
                                               AgentProperties properties) {
        return new ModelDefinition(properties.getLlm().getQwenModelName(), qwenChatModel);
    }
}
