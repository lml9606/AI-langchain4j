package com.example.langchain4j.business.quota;

import com.example.langchain4j.business.enrollment.EnrollmentService;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;

/**
 * 额度服务：按用户 + 活动 + 月份维护总额度和已用额度。
 *
 * 可用额度由实体计算，不落库；额度更新时校验 usedAmount <= totalAmount，避免负可用额度。
 */
@Slf4j
@Service
public class QuotaService {

    private static final int MAX_PAGE_SIZE = 100;
    private final QuotaMapper quotaMapper;
    private final EnrollmentService enrollmentService;

    /** 注入额度 Mapper 和报名服务；报名单号关联时复用报名归属校验。 */
    public QuotaService(QuotaMapper quotaMapper, EnrollmentService enrollmentService) {
        this.quotaMapper = quotaMapper;
        this.enrollmentService = enrollmentService;
    }

    /** 创建额度记录；活动编号和月份由业务请求指定。 */
    @Transactional(rollbackFor = Exception.class)
    public Quota create(String userId, QuotaRequest request) {
        Quota quota = new Quota();
        quota.setUserId(userId);
        apply(quota, request, true);
        quotaMapper.insert(quota);
        log.info("[quota] created, id={}, activityNo={}, enrollmentNo={}, month={}, user={}, total={}, used={}",
                quota.getId(), quota.getActivityNo(), quota.getEnrollmentNo(), quota.getMonth(), userId,
                quota.getTotalAmount(), quota.getUsedAmount());
        return requireOwned(quota.getId(), userId);
    }

    /** 按 ID 查询当前用户的额度记录。 */
    public Quota get(long id, String userId) {
        return requireOwned(id, userId);
    }

    /** 查询当前用户的额度记录列表。 */
    public List<Quota> list(String userId, int limit) {
        return list(userId, null, null, null, limit);
    }

    /** 按活动编号和月份查询当前用户的额度记录；两个条件为空表示不过滤。 */
    public List<Quota> list(String userId, String activityNo, String month, int limit) {
        return list(userId, null, activityNo, month, limit);
    }

    /** 按报名单号、活动编号和月份查询当前用户的额度记录；条件为空表示不过滤。 */
    public List<Quota> list(String userId, String enrollmentNo, String activityNo, String month,
                            int limit) {
        List<Quota> list = quotaMapper.selectByUserId(userId,
                trimToNull(enrollmentNo), trimToNull(activityNo), trimToNull(month),
                safeLimit(limit));
        return list == null ? List.of() : list;
    }

    /** 全量更新额度记录；活动编号、月份和金额都可更新。 */
    @Transactional(rollbackFor = Exception.class)
    public Quota update(long id, String userId, QuotaRequest request) {
        Quota quota = requireOwned(id, userId);
        apply(quota, request, false);
        if (quotaMapper.update(quota) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "额度记录不存在或无权操作: " + id);
        }
        log.info("[quota] updated, id={}, user={}, enrollmentNo={}, total={}, used={}",
                id, userId, quota.getEnrollmentNo(), quota.getTotalAmount(), quota.getUsedAmount());
        return requireOwned(id, userId);
    }

    /** 删除当前用户的额度记录。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(long id, String userId) {
        if (quotaMapper.delete(id, userId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "额度记录不存在或无权操作: " + id);
        }
        log.info("[quota] deleted, id={}, user={}", id, userId);
    }

    /** 按 ID + 用户查询额度；查不到和越权统一转 404。 */
    private Quota requireOwned(long id, String userId) {
        Quota quota = quotaMapper.selectById(id, userId);
        if (quota == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "额度记录不存在或无权操作: " + id);
        }
        return quota;
    }

    /** 校验并写入额度字段：金额边界、月份格式和报名单号归属都在落库前检查。 */
    private void apply(Quota quota, QuotaRequest request, boolean create) {
        BigDecimal total = request.totalAmount();
        BigDecimal used = request.usedAmount() != null
                ? request.usedAmount()
                : (create ? BigDecimal.ZERO : quota.getUsedAmount());
        if (total == null || total.signum() < 0 || used == null || used.signum() < 0
                || used.compareTo(total) > 0) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "额度金额必须满足 0 <= usedAmount <= totalAmount");
        }
        String month = request.month() == null ? "" : request.month().trim();
        if (!month.matches("\\d{4}-(0[1-9]|1[0-2])")) {
            throw new BizException(ErrorCode.BAD_REQUEST, "月份格式必须为 YYYY-MM");
        }
        quota.setActivityNo(request.activityNo().trim());
        String enrollmentNo = trimToNull(request.enrollmentNo());
        if (enrollmentNo != null
                && enrollmentService.findByNo(quota.getUserId(), enrollmentNo) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "报名单号不存在或不属于当前用户: " + enrollmentNo);
        }
        quota.setEnrollmentNo(enrollmentNo);
        quota.setMonth(month);
        quota.setQuotaType(trimToNull(request.quotaType()) == null
                ? defaultQuotaType(quota) : request.quotaType().trim().toUpperCase(Locale.ROOT));
        quota.setTotalAmount(total);
        quota.setUsedAmount(used);
    }

    /** 未指定额度类型时沿用已有值；新建且未指定时使用 DEFAULT。 */
    private String defaultQuotaType(Quota quota) {
        return quota.getQuotaType() == null || quota.getQuotaType().isBlank()
                ? "DEFAULT" : quota.getQuotaType();
    }

    /** 把分页大小限制在 1~100，防止一次查询拉取过量数据。 */
    private int safeLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    }

    /** 去空白后空串转 null，统一数据库中的空值语义。 */
    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
