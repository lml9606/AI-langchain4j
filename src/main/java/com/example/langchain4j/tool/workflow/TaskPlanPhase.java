package com.example.langchain4j.tool.workflow;

/**
 * 任务计划所处的校验阶段。
 *
 * <p>阶段会影响“写操作是否必须存在”：规划阶段允许只读计划；确认阶段必须恰好包含一个写步骤；
 * 执行阶段再次复核写操作数量，防止确认后的计划被篡改。
 */
public enum TaskPlanPhase {
    PLAN,
    CONFIRM,
    EXECUTE
}
