package com.example.langchain4j.Interface;

import dev.langchain4j.internal.Json;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.Result;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.codec.ServerSentEvent;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-03-29 19:16
 * @Modify:
 **/
@SpringBootTest
class QwenInterfaceTest {

    @Resource
    private QwenInterface qwenInterface;

    @Test
    void chat() {
        String result = qwenInterface.chat("你好,我是程序员lml");
        System.out.println(result);
    }

    @Test
    void chatByMemoryId() {
        String result = qwenInterface.chatByMemoryId("userA", "你好,我是程序员lml");
        System.out.println(result);
        result = qwenInterface.chatByMemoryId("userB", "我是谁来着？");
        System.out.println(result);
        result = qwenInterface.chatByMemoryId("userA", "我是谁来着");
        System.out.println(result);
    }

    @Test
    void chatByReport() {
        String result = qwenInterface.chatByReport("你好,我是程序员lml,我想学习langchain4j,你有什么好的学习方案给到我么？");
        System.out.println(result);
    }

    @Test
    void chatByFunctionCalling() {
        QwenInterface.UserPlanInfo result = qwenInterface.chatByFunctionCalling("你好,我是程序员lml,我想学习langchain4j,你有什么好的学习方案给到我么？");
        System.out.println(Json.toJson(result));
    }


    @Test
    void chatWithRag() {
        Result<String> result = qwenInterface.chatWithRag("你好,我是程序员lml,我想学习langchain4j,你有什么好的学习方案给到我么？");
        String content = result.content();
        List<Content> sources = result.sources();
        System.out.println(content);
        System.out.println(sources);
    }

    @Test
    void chatWithStream() {
        //测试方法 主线程太快，导致大模型还没返回 主线程就完成。 可以增加等待时间或者 在controller执行 controller 会自动调用 subscribe
        //流式输出 result 并不是实际返回值 subscribe后才会输出
        Flux<String> result = qwenInterface.chatWithStream("你好,我是程序员lml,我想学习langchain4j,你有什么好的学习方案给到我么？");
        result.subscribe(word-> System.out.print(word),error-> System.out.println(error),()-> System.out.println("end"));

    }
}