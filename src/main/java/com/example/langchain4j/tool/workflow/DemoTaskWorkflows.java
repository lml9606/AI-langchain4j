package com.example.langchain4j.tool.workflow;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 演示任务契约：工具集合、阶段和顺序在代码中固定，模型不能自由增删。
 *
 * <p>接入真实业务时，新增一个 {@link TaskWorkflow} Bean 即可；不要再把工具白名单
 * 同时写进配置文件和数据库，否则两处策略漂移后无法保证权限判断一致。
 */
@Configuration(proxyBeanMethods = false)
public class DemoTaskWorkflows {

    @Bean
    public TaskWorkflow demoRecordQueryWorkflow() {
        return new SimpleTaskWorkflow(
                "demo_record_query",
                "查询、查看当前用户的演示任务记录；支持按状态筛选和查看最近记录",
                List.of(new TaskWorkflow.Stage(
                        "query", "查询当前用户的演示记录", List.of("query_demo_record"))));
    }

    @Bean
    public TaskWorkflow demoRecordCompleteWorkflow() {
        return new SimpleTaskWorkflow(
                "demo_record_complete",
                "把当前用户的一条演示任务标记为完成；这是写操作，执行前必须人工确认",
                List.of(new TaskWorkflow.Stage(
                        "complete", "定位并完成演示记录", List.of("complete_demo_record"))));
    }

    private record SimpleTaskWorkflow(String id, String description, List<Stage> stages)
            implements TaskWorkflow {
    }
}
