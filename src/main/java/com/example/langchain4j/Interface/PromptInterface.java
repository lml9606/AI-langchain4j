package com.example.langchain4j.Interface;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 16:32
 * @Modify:
 **/
public interface PromptInterface {

    @SystemMessage(fromResource = "static/systemPrompt/system-prompt.txt")
    public String chatByUserIdAndSys(@MemoryId String userId, @UserMessage String userMessage);
}
