package com.example.langchain4j.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 报名创建/更新请求：userId 不由客户端传入，统一从登录上下文获取。
 */
public record EnrollmentRequest(
        @NotBlank(message = "applicantName 不能为空")
        @Size(max = 64, message = "applicantName 长度不能超过 64")
        String applicantName,

        @Size(max = 32, message = "phone 长度不能超过 32")
        String phone,

        @DecimalMin(value = "0.00", message = "amount 不能小于 0")
        BigDecimal amount,

        @Size(max = 500, message = "remark 长度不能超过 500")
        String remark) {
}
