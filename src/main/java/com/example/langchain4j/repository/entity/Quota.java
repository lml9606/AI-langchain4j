package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 用户活动月度额度：按活动编号和月份维护总额度、已用额度。
 */
@Data
public class Quota {

    private Long id;
    /** 活动编号。 */
    private String activityNo;
    private String userId;
    /** 额度月份，格式 YYYY-MM。 */
    private String month;
    private String quotaType;
    private BigDecimal totalAmount;
    private BigDecimal usedAmount;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    /** 可用额度由总额度减已用额度计算，不单独落库，避免三个值不一致。 */
    public BigDecimal getAvailableAmount() {
        BigDecimal total = totalAmount == null ? BigDecimal.ZERO : totalAmount;
        BigDecimal used = usedAmount == null ? BigDecimal.ZERO : usedAmount;
        return total.subtract(used);
    }
}
