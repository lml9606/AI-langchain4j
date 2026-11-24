package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.dto.request.QuotaRequest;
import com.example.langchain4j.repository.entity.Quota;
import com.example.langchain4j.repository.mapper.QuotaMapper;
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

    public QuotaService(QuotaMapper quotaMapper) {
        this.quotaMapper = quotaMapper;
    }

    /** 创建额度记录；活动编号和月份由业务请求指定。 */
    @Transactional(rollbackFor = Exception.class)
    public Quota create(String userId, QuotaRequest request) {
        Quota quota = new Quota();
        quota.setUserId(userId);
        apply(quota, request, true);
        quotaMapper.insert(quota);
        log.info("[quota] created, id={}, activityNo={}, month={}, user={}, total={}, used={}",
                quota.getId(), quota.getActivityNo(), quota.getMonth(), userId,
                quota.getTotalAmount(), quota.getUsedAmount());
        return requireOwned(quota.getId(), userId);
    }

    /** 按 ID 查询当前用户的额度记录。 */
    public Quota get(long id, String userId) {
        return requireOwned(id, userId);
    }

    /** 查询当前用户的额度记录列表。 */
    public List<Quota> list(String userId, int limit) {
        List<Quota> list = quotaMapper.selectByUserId(userId, safeLimit(limit));
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
        log.info("[quota] updated, id={}, user={}, total={}, used={}",
                id, userId, quota.getTotalAmount(), quota.getUsedAmount());
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

    private Quota requireOwned(long id, String userId) {
        Quota quota = quotaMapper.selectById(id, userId);
        if (quota == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "额度记录不存在或无权操作: " + id);
        }
        return quota;
    }

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
        quota.setMonth(month);
        quota.setQuotaType(trimToNull(request.quotaType()) == null
                ? defaultQuotaType(quota) : request.quotaType().trim().toUpperCase(Locale.ROOT));
        quota.setTotalAmount(total);
        quota.setUsedAmount(used);
    }

    private String defaultQuotaType(Quota quota) {
        return quota.getQuotaType() == null || quota.getQuotaType().isBlank()
                ? "DEFAULT" : quota.getQuotaType();
    }

    private int safeLimit(int limit) {
        return Math.min(Math.max(limit, 1), MAX_PAGE_SIZE);
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

}
