package com.example.langchain4j.tool.runtime;

/**
 * 工具执行错误码：前端、图节点和审计共同依赖的稳定契约。
 *
 * 错误码只描述“执行结果类别”，不描述具体业务原因；业务原因放在 message 里。
 * 新增错误码时保持枚举名不变，避免前端和任务观察逻辑因字符串漂移而失效。
 */
public enum ToolErrorCode {
    UNKNOWN_TOOL,
    CONFIRMATION_REQUIRED,
    MISSING_IDEMPOTENCY_KEY,
    INVALID_ARGS,
    RATE_LIMITED,
    OVERLOADED,
    EMPTY_RESULT,
    INTERRUPTED,
    TIMEOUT,
    FAILED,
    FORBIDDEN_WRITE,
    FORBIDDEN_TOOL
}
