package com.example.langchain4j.tool.api.model;

/** 工具副作用级别：执行器据此决定是否需要人工确认。 */
public enum ToolSideEffect {
    /** 只读：不产生业务副作用，可直接执行。 */
    READ_ONLY,

    /** 写操作：必须经过人工确认和幂等校验。 */
    WRITE,

    /** 高危写操作：保留给需要更严格审批的业务，当前执行器按写操作处理。 */
    DANGEROUS
}
