package com.example.langchain4j.common.exception;

import com.example.langchain4j.common.response.ApiResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常兜底：任何未捕获异常都转换为统一响应，绝不向客户端泄漏堆栈。
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    /** 业务异常：按错误码返回 */
    @ExceptionHandler(BizException.class)
    public ApiResponse<Void> handleBizException(BizException e) {
        log.warn("[biz] code={}, message={}", e.getCode(), e.getMessage());
        return ApiResponse.error(e.getCode(), e.getMessage());
    }

    /** 参数校验失败（@Valid 注解触发） */
    @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidationException(Exception e) {
        String detail = "参数校验失败";
        if (e instanceof MethodArgumentNotValidException ex && ex.getBindingResult().getFieldError() != null) {
            FieldError fe = ex.getBindingResult().getFieldError();
            detail = String.format("参数校验失败: %s %s", fe.getField(), fe.getDefaultMessage());
        } else if (e instanceof BindException ex && ex.getBindingResult().getFieldError() != null) {
            FieldError fe = ex.getBindingResult().getFieldError();
            detail = String.format("参数校验失败: %s %s", fe.getField(), fe.getDefaultMessage());
        }
        log.warn("[validate] {}", detail);
        return ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), detail);
    }

    /** 缺参 / 参数类型不匹配 */
    @ExceptionHandler({MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class})
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleBadRequest(Exception e) {
        log.warn("[bad-request] {}", e.getMessage());
        return ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), ErrorCode.BAD_REQUEST.getMessage());
    }

    /** 数据库异常：脱敏后返回，细节只进日志 */
    @ExceptionHandler(DataAccessException.class)
    public ApiResponse<Void> handleDataAccessException(DataAccessException e) {
        log.error("[db] data access failure", e);
        return ApiResponse.error(ErrorCode.DB_UNAVAILABLE.getCode(), ErrorCode.DB_UNAVAILABLE.getMessage());
    }

    /**
     * 路径不存在：Spring Boot 3.2+ 中 404 以 NoResourceFoundException 抛出，
     * 必须在此拦截返回 404 语义，否则会落入兜底被伪装成“系统内部错误”。
     */
    @ExceptionHandler(NoResourceFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiResponse<Void> handleNoResourceFound(NoResourceFoundException e) {
        log.warn("[404] resource not found: {}", e.getResourcePath());
        return ApiResponse.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getMessage());
    }

    /** 请求方法不支持（如对 POST 接口发 GET）：405 语义，同样避免落入兜底伪装成 500 */
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    @ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
    public ApiResponse<Void> handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
        log.warn("[405] method not allowed: {} {}", e.getMethod(), e.getMessage());
        return ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), "请求方法不支持");
    }

    /** 兜底：未知异常 */
    @ExceptionHandler(Exception.class)
    public ApiResponse<Void> handleUnknownException(Exception e) {
        log.error("[internal] unexpected error", e);
        return ApiResponse.error(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
    }
}
