package com.example.langchain4j.Interface;

import dev.langchain4j.service.MemoryId;
import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import reactor.core.publisher.Flux;

/**
 * @Description :声明式调用大模型
 * @Reference :
 * @Author :
 * @CreateDate : 2026-03-29 18:58
 * @Modify:
 **/
public interface QwenInterface {


    //提示词模式
    @SystemMessage(fromResource = "static/systemPrompt/system-prompt.txt")
    public String chat(String userMessage);

    //userId区分用户
    @SystemMessage(fromResource = "static/systemPrompt/system-prompt.txt")

    public String chatByMemoryId(@MemoryId String memoryId, @UserMessage String userMessage);


    //根据提示词生成 优点灵活 缺点 通过提示词的方式告诉大模型生成 大模型有可能不按照提示词的方式生成 依赖模型
    @SystemMessage(fromResource = "static/systemPrompt/system-prompt.txt")
    public String chatByReport(String userMessage);

    //根据function calling 生成 优点可靠性高，缺点有实体限制 大模型返回的会与实体不匹配 需要预设定 灵活度低
    @SystemMessage(fromResource = "static/systemPrompt/system-prompt.txt")
    public UserPlanInfo chatByFunctionCalling(String userMessage);

    record UserPlanInfo(String UserId, String planName,String planInfo){}

    //RAG 查询
    @SystemMessage(fromResource = "static/systemPrompt/system-prompt.txt")
    public Result<String> chatWithRag(String userMessage);

    // 流式对话
    @SystemMessage(fromResource = "static/systemPrompt/system-prompt.txt")
    Flux<String> chatWithStream( String userMessage);





}
