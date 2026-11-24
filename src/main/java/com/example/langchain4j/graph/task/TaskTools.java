package com.example.langchain4j.graph.task;

/**
 * 固定任务链路使用的工具名：节点内写死，模型只能填参数，不能选择或替换工具。
 */
public final class TaskTools {

    /** 只读工具：查询当前用户的演示记录。 */
    public static final String READ = "query_demo_record";

    /** 写工具：把当前用户的一条演示记录标记为完成。 */
    public static final String WRITE = "complete_demo_record";

    private TaskTools() {
    }
}
