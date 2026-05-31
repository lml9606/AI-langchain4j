package com.example.langchain4j.service.summary;

import com.example.langchain4j.entity.ChatMessage;
import com.example.langchain4j.service.persistence.ChatPersistenceService;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 16:06
 * @Modify:
 **/
@Service
public class ChatSummaryService {

    @Resource
    private ChatModel qwenChatModel;

    @Resource
    private ChatPersistenceService persistenceService;


    public void checkAndSummarize(Long sessionId) {
        int count = persistenceService.countBySessionIdAndSummarizedFalse(sessionId);

        if (count > 20) {
            doSummarize(sessionId);
        }
    }

    private void doSummarize(Long sessionId) {


        //获取需要摘要的信息
        List<ChatMessage> chatMessageList = persistenceService.getMessageBySummarize(sessionId, 10);

        //todo 构建摘要prompt

        //todo 调用模型进行摘要

        //todo 更新摘要信息


    }

}
