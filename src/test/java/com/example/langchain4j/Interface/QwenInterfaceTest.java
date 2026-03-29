package com.example.langchain4j.Interface;

import jakarta.annotation.Resource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

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
}