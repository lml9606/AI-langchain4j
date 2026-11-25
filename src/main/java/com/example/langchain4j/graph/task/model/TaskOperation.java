package com.example.langchain4j.graph.task.model;

/**
 * 任务操作类型：固定链路执行 READ 和 WRITE，REACT 走受控只读诊断分支。
 */
public enum TaskOperation {

    /** 只读查询：不产生业务副作用，不需要确认。 */
    READ,

    /** 写操作：必须经过人工确认并携带幂等键。 */
    WRITE,

    /** 受控 ReAct 只读诊断：只在白名单只读工具间循环，步数有上限。 */
    REACT;

    /** 解析模型输出；未知值返回 null，由调用方 fail-closed。 */
    public static TaskOperation parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase();
        return switch (normalized) {
            case "READ" -> READ;
            case "WRITE" -> WRITE;
            case "REACT" -> REACT;
            default -> null;
        };
    }
}
