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
                "先查询当前用户的演示任务，再把指定记录标记为完成；写操作执行前必须人工确认",
                List.of(
                        new TaskWorkflow.Stage(
                                "query", "查询候选记录，确认要修改哪一条",
                                List.of("query_demo_record")),
                        new TaskWorkflow.Stage(
                                "complete", "完成指定记录（可引用查询结果中的 recordId）",
                                List.of("complete_demo_record"))));
    }

    /**
     * 多任务演示：2 个只读步骤并行查询，随后 3 个写步骤批量确认。
     * <p>
     * 写步骤通过 {@code $ref} 引用第 0 步返回的前三条待办记录：
     * {@code 0.0.id / 0.1.id / 0.2.id}。这用来验证：
     * 同阶段只读并行、批量确认、逐步幂等、单步失败后跳过后续步骤。
     */
    @Bean
    public TaskWorkflow demoRecordBatchCompleteWorkflow() {
        return new SimpleTaskWorkflow(
                "demo_record_batch_complete",
                "批量完成当前用户的演示任务：先并行查询待办和已完成记录，"
                        + "再把待办查询结果中的前三条记录依次标记为完成；"
                        + "三个写步骤合并成一次用户确认。",
                List.of(
                        new TaskWorkflow.Stage(
                                "query", "并行查询待办和已完成记录，为写步骤提供 recordId",
                                List.of("query_demo_record", "query_demo_record"), true),
                        new TaskWorkflow.Stage(
                                "complete", "依次完成第 1 个查询步骤返回的前三条待办记录",
                                List.of("complete_demo_record", "complete_demo_record",
                                        "complete_demo_record"))));
    }

    private record SimpleTaskWorkflow(String id, String description, List<Stage> stages)
            implements TaskWorkflow {
    }
}
