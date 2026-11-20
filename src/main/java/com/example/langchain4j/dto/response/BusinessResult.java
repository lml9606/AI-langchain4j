package com.example.langchain4j.dto.response;

/**
 * 业务执行结果：接口返回成功不代表写工具等业务动作一定成功。
 */
public record BusinessResult(boolean success, String errorCode, String errorMessage) {

    public BusinessResult {
        errorCode = errorCode == null ? "" : errorCode;
        errorMessage = errorMessage == null ? "" : errorMessage;
    }

    public static BusinessResult ok() {
        return new BusinessResult(true, "", "");
    }

    public static BusinessResult failure(String errorCode, String errorMessage) {
        return new BusinessResult(false, errorCode, errorMessage);
    }
}
