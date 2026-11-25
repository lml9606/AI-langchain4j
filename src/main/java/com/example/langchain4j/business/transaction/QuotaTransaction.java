package com.example.langchain4j.business.transaction;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 额度交易流水：记录一次额度变动及其关联业务。
 */
@Data
public class QuotaTransaction {

    public static final String TYPE_DEDUCT = "DEDUCT";
    public static final String TYPE_REFUND = "REFUND";
    public static final String TYPE_ADJUST = "ADJUST";
    public static final String TYPE_RECHARGE = "RECHARGE";

    private Long id;
    /** 交易流水号，对外展示使用。 */
    private String transactionNo;
    private String userId;
    /** 所属额度记录。 */
    private Long quotaId;
    /** 可选关联的报名单。 */
    private Long enrollmentId;
    /** DEDUCT / REFUND / ADJUST / RECHARGE */
    private String transactionType;
    private BigDecimal amount;
    private String remark;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
