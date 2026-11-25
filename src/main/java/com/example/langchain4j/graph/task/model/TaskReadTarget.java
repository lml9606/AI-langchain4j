package com.example.langchain4j.graph.task.model;

/**
 * 固定只读任务的目标表：模型只能选择这三类，不能指定具体工具名。
 */
public enum TaskReadTarget {

    /** 报名记录。 */
    ENROLLMENT,

    /** 额度记录。 */
    QUOTA,

    /** 额度交易流水。 */
    TRANSACTION;

    /** 解析模型输出；未知值返回 null，由调用方 fail-closed。 */
    public static TaskReadTarget parse(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return switch (value.trim().toUpperCase()) {
            case "ENROLLMENT" -> ENROLLMENT;
            case "QUOTA" -> QUOTA;
            case "TRANSACTION" -> TRANSACTION;
            default -> null;
        };
    }
}
