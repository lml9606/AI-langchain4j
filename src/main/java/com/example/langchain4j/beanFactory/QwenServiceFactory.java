package com.example.langchain4j.beanFactory;

import com.example.langchain4j.Interface.QwenInterface;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.ChatMemoryProvider;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.service.AiServices;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-03-29 19:21
 * @Modify:
 **/
@Component
public class QwenServiceFactory {

    @Resource
    private ChatModel qwenChatModel;

    @Resource
    private ContentRetriever contentRetriever;

    @Bean
    public QwenInterface qwenInterface() {
        //从内存中获取userId 如果没有 则创建 获取历史10条消息
        Map<String, ChatMemory> chatMemoryMap = new HashMap<>();
        ChatMemoryProvider provider = memoryId -> {
            return chatMemoryMap.computeIfAbsent((String) memoryId, k -> MessageWindowChatMemory.builder().maxMessages(10).build());
        };

        return AiServices.builder(QwenInterface.class)
                .chatModel(qwenChatModel)
                .contentRetriever(contentRetriever) // RAG 检索增强生成
                .chatMemoryProvider(provider) // 内存存储
                .build();
    }
}
