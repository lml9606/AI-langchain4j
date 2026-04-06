package com.example.langchain4j.service;

import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-03-29 22:47
 * @Modify:
 **/
@Service
@Slf4j
public class chatUserMessage {

    @Resource
    private ChatModel qwenChatModel;

    public String chat(String message) {
        //编程式接入大模型
        UserMessage userMessage = UserMessage.from(message);
        ChatResponse response = qwenChatModel.chat(userMessage);
        AiMessage message1 = response.aiMessage();
        log.info("大模型返回内容：{}", message1.text());
        return message1.text();
    }
}
