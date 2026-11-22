package com.example.langchain4j.tool.workflow;

/**
 * 通用校验结论。
 *
 * <p>code 用于程序判定和日志聚合，message 用于返回给用户或写入任务轨迹。
 * 校验失败采用 fail-closed：调用方看到 {@code valid=false} 后必须终止当前动作。
 */
public record ValidationResult(boolean valid, String code, String message) {

    public ValidationResult {
        code = code == null ? "" : code.trim();
        message = message == null ? "" : message.trim();
    }

    public static ValidationResult ok() {
        return new ValidationResult(true, "", "");
    }

    public static ValidationResult invalid(String code, String message) {
        return new ValidationResult(false, code, message);
    }

    /** 扩展规则常用入口：不需要区分错误码时只传用户可读原因。 */
    public static ValidationResult invalid(String message) {
        return invalid("VALIDATION_FAILED", message);
    }
}
