package com.example.langchain4j;

import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Spring Boot 启动类：开启异步任务和定时任务，供摘要、清理等后台能力使用。 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
public class Langchain4jApplication {

    /** 启动应用容器；参数原样交给 Spring Boot 处理。 */
    public static void main(String[] args) {
        SpringApplication.run(Langchain4jApplication.class, args);
    }

}
