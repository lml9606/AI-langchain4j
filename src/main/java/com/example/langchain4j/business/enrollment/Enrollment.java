package com.example.langchain4j.business.enrollment;

import lombok.Data;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

/**
 * 报名记录：用户对课程或活动的报名事实。
 */
@Data
public class Enrollment {

    private Long id;
    /** 报名单号，对外展示和幂等定位使用。 */
    private String enrollmentNo;
    /** 报名用户；所有 SQL 读写都必须带该字段过滤。 */
    private String userId;
    private String applicantName;
    private String phone;
    /** 报名金额。 */
    private BigDecimal amount;
    private String remark;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
