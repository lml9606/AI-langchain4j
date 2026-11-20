package com.example.langchain4j.common.response;

import com.example.langchain4j.common.exception.ErrorCode;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 接口层响应基类：只表达请求在接口层是否成功，不承载业务执行结果。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class BaseResponse implements Serializable {

    private int code = ErrorCode.SUCCESS.getCode();
    private String message = ErrorCode.SUCCESS.getMessage();

    /** 接口层是否成功；业务是否成功由具体响应对象承载。 */
    public boolean isSuccess() {
        return code == ErrorCode.SUCCESS.getCode();
    }
}
