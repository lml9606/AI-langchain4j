package com.example.langchain4j.dto.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 额度账户创建/更新请求。
 */
public record QuotaRequest(
        @Size(max = 64, message = "enrollmentNo 长度不能超过 64")
        String enrollmentNo,

        @NotBlank(message = "activityNo 不能为空")
        @Size(max = 64, message = "activityNo 长度不能超过 64")
        String activityNo,

        @NotBlank(message = "month 不能为空")
        @Pattern(regexp = "\\d{4}-(0[1-9]|1[0-2])", message = "month 格式必须为 YYYY-MM")
        String month,

        @Size(max = 32, message = "quotaType 长度不能超过 32")
        String quotaType,

        @NotNull(message = "totalAmount 不能为空")
        @DecimalMin(value = "0.00", message = "totalAmount 不能小于 0")
        BigDecimal totalAmount,

        @DecimalMin(value = "0.00", message = "usedAmount 不能小于 0")
        BigDecimal usedAmount) {
}
