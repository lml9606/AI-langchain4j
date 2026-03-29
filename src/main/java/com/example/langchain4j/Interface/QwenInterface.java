package com.example.langchain4j.Interface;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * @Description :声明式调用大模型
 * @Reference :
 * @Author :
 * @CreateDate : 2026-03-29 18:58
 * @Modify:
 **/
public interface QwenInterface {


    //提示词模式
    @SystemMessage(fromResource = "static/system-prompt.txt")
    public String chat( String userMessage);

    //userId区分用户
    @SystemMessage(fromResource = "static/system-prompt.txt")
    public String chatByMemoryId(@MemoryId String memoryId, @UserMessage String userMessage);


}
