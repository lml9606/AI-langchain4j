package com.example.langchain4j.service;

import com.example.langchain4j.Interface.PromptInterface;
import com.example.langchain4j.Interface.QwenInterface;
import com.example.langchain4j.entity.ChatSession;
import com.example.langchain4j.service.persistence.ChatPersistenceService;
import com.example.langchain4j.service.summary.ChatSummaryService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-30 17:50
 * @Modify:
 **/
@Slf4j
@Service
public class PromptGeneratorService {

    @Resource
    private QwenInterface qwenInterface;

    @Resource
    private PromptInterface promptInterface;

    @Resource
    private ChatPersistenceService persistenceService;

    @Resource
    private ChatSummaryService summaryService;

    public String generatePrompt(String userId, String prompt) {

        //1、保存数据库
        persistenceService.saveUserMessage(userId, prompt);

        //2、调用接口
        String aiReSult = promptInterface.chatByUserIdAndSys(userId, prompt);

        //3、异步保存数据库
        saveUserMessage(userId, aiReSult);
        //4、异步检查触发摘要
        checkAndSummarize(userId);

        return aiReSult;
    }

    @Async
    public void saveUserMessage(String userId, String content) {
        persistenceService.saveUserMessage(userId, content);
    }

    @Async
    public void checkAndSummarize(String userId) {

        ChatSession chatSession = persistenceService.getSession(userId);

        if(chatSession != null) {
            summaryService.checkAndSummarize(chatSession.getId());
        }


    }


}
