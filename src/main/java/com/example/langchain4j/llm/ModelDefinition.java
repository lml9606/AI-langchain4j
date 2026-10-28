package com.example.langchain4j.llm;

import dev.langchain4j.model.chat.ChatModel;

/**
 * 模型注册定义：模型名 + ChatModel 实例。
 * <p>
 * 各模型 Config 各自产出本 Bean（千问见 QwenConfig，DeepSeek 见 DeepSeekConfig），
 * ModelRegistry 通过 {@code List<ModelDefinition>} 汇总成"模型名 → ChatModel"路由表；
 * 新增模型只需新增一个 ModelDefinition Bean，无需改动注册表。
 */
public record ModelDefinition(String name, ChatModel model) {
}
