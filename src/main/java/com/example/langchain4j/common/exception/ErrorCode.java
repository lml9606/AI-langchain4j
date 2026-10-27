package com.example.langchain4j.common.exception;

/**
 * 统一错误码定义。
 * 分类约定：4xxx 客户端错误（可预期），5xxx 服务端错误（需关注告警）。
 */
public enum ErrorCode {

    SUCCESS(0, "成功"),

    // ---- 通用 ----
    BAD_REQUEST(40000, "请求参数错误"),
    UNAUTHORIZED(40100, "未认证"),
    FORBIDDEN(40300, "无权限"),
    NOT_FOUND(40400, "资源不存在"),
    TOO_MANY_REQUESTS(42900, "请求过于频繁，请稍后重试"),

    // ---- Agent 业务 ----
    AGENT_RATE_LIMITED(42901, "触发限流，请稍后重试"),
    AGENT_SESSION_LOCKED(40901, "会话正在处理中，请勿重复提交"),
    AGENT_EXECUTION_FAILED(50010, "Agent 执行失败"),
    AGENT_LLM_UNAVAILABLE(50300, "大模型服务暂时不可用，已降级处理"),
    AGENT_TIMEOUT(50400, "Agent 执行超时"),

    // ---- 基础设施 ----
    DB_UNAVAILABLE(50310, "数据库服务暂时不可用"),
    CACHE_UNAVAILABLE(50311, "缓存服务暂时不可用"),
    INTERNAL_ERROR(50000, "系统内部错误");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
