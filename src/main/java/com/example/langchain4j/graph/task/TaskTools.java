package com.example.langchain4j.graph.task;

/**
 * 固定任务链路使用的操作标识：模型只能填参数，不能选择或替换操作。
 *
 * 固定读查询由节点直接调用领域 Service，这里的 READ_* 同时作为轨迹标识和
 * ReAct 白名单只读工具名；
 * WRITE 仍是注册到 ToolRegistry 的真实写工具，需要确认和幂等。
 */
public final class TaskTools {

    /** 只读操作标识：查询当前用户的报名记录。 */
    public static final String READ_ENROLLMENT = "query_enrollment";

    /** 只读操作标识：查询当前用户的额度记录。 */
    public static final String READ_QUOTA = "query_quota";

    /** 只读操作标识：查询当前用户的额度交易流水。 */
    public static final String READ_TRANSACTION = "query_transaction";

    /** 写工具：把当前用户的一条演示记录标记为完成。 */
    public static final String WRITE = "complete_demo_record";

    private TaskTools() {
    }
}
