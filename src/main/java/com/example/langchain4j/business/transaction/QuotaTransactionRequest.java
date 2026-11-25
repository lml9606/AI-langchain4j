package com.example.langchain4j.business.transaction;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

/**
 * 额度交易创建/更新请求。
 */
public record QuotaTransactionRequest(
        @NotNull(message = "quotaId 不能为空")
        Long quotaId,

        Long enrollmentId,

        @NotBlank(message = "transactionType 不能为空")
        @Size(max = 32, message = "transactionType 长度不能超过 32")
        String transactionType,

        @NotNull(message = "amount 不能为空")
        @DecimalMin(value = "0.01", message = "amount 必须大于 0")
        BigDecimal amount,

        @Size(max = 500, message = "remark 长度不能超过 500")
        String remark) {
}
