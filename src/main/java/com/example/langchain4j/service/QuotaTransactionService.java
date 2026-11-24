package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.dto.request.QuotaTransactionRequest;
import com.example.langchain4j.repository.entity.QuotaTransaction;
import com.example.langchain4j.repository.mapper.EnrollmentMapper;
import com.example.langchain4j.repository.mapper.QuotaMapper;
import com.example.langchain4j.repository.mapper.QuotaTransactionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 额度交易流水服务：维护交易事实，不在这里隐式修改额度账户。
 *
 * 额度扣减、退款等跨表业务应由专门的业务编排在同一事务中同时写流水和更新额度；
 * 这样做可以避免普通 CRUD 的修改、删除操作产生无法追踪的反向记账。
 */
@Slf4j
@Service
public class QuotaTransactionService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter NO_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private static final Set<String> ALLOWED_TYPE = Set.of(
            QuotaTransaction.TYPE_DEDUCT,
            QuotaTransaction.TYPE_REFUND,
            QuotaTransaction.TYPE_ADJUST,
            QuotaTransaction.TYPE_RECHARGE);
    private final QuotaTransactionMapper transactionMapper;
    private final QuotaMapper quotaMapper;
    private final EnrollmentMapper enrollmentMapper;

    public QuotaTransactionService(QuotaTransactionMapper transactionMapper,
                                   QuotaMapper quotaMapper,
                                   EnrollmentMapper enrollmentMapper) {
        this.transactionMapper = transactionMapper;
        this.quotaMapper = quotaMapper;
        this.enrollmentMapper = enrollmentMapper;
    }

    /** 创建交易流水；quotaId 和 enrollmentId 必须属于当前用户。 */
    @Transactional(rollbackFor = Exception.class)
    public QuotaTransaction create(String userId, QuotaTransactionRequest request) {
        QuotaTransaction transaction = new QuotaTransaction();
        transaction.setTransactionNo(generateNo("TX"));
        transaction.setUserId(userId);
        apply(transaction, request);
        validateReferences(transaction, userId);
        transactionMapper.insert(transaction);
        log.info("[transaction] created, id={}, no={}, user={}, quotaId={}, type={}, amount={}",
                transaction.getId(), transaction.getTransactionNo(), userId,
                transaction.getQuotaId(), transaction.getTransactionType(), transaction.getAmount());
        return requireOwned(transaction.getId(), userId);
    }

    /** 按 ID 查询当前用户的交易流水。 */
    public QuotaTransaction get(long id, String userId) {
        return requireOwned(id, userId);
    }

    /** 查询当前用户的交易流水；quotaId 为空表示不过滤。 */
    public List<QuotaTransaction> list(String userId, Long quotaId, int limit) {
        List<QuotaTransaction> list = transactionMapper.selectByUserId(userId, quotaId,
                safeLimit(limit));
        return list == null ? List.of() : list;
    }

    /** 全量更新交易流水；变更关联账户或报名单时重新校验归属。 */
    @Transactional(rollbackFor = Exception.class)
    public QuotaTransaction update(long id, String userId, QuotaTransactionRequest request) {
        QuotaTransaction transaction = requireOwned(id, userId);
        apply(transaction, request);
        validateReferences(transaction, userId);
        if (transactionMapper.update(transaction) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "交易流水不存在或无权操作: " + id);
        }
        log.info("[transaction] updated, id={}, user={}", id, userId);
        return requireOwned(id, userId);
    }

    /** 删除当前用户的交易流水。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(long id, String userId) {
        if (transactionMapper.delete(id, userId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "交易流水不存在或无权操作: " + id);
        }
        log.info("[transaction] deleted, id={}, user={}", id, userId);
    }

    private QuotaTransaction requireOwned(long id, String userId) {
        QuotaTransaction transaction = transactionMapper.selectById(id, userId);
        if (transaction == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "交易流水不存在或无权操作: " + id);
        }
        return transaction;
    }

    private void apply(QuotaTransaction transaction, QuotaTransactionRequest request) {
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "交易金额必须大于 0");
        }
        transaction.setQuotaId(request.quotaId());
        transaction.setEnrollmentId(request.enrollmentId());
        transaction.setTransactionType(normalizeType(request.transactionType()));
        transaction.setAmount(request.amount());
        transaction.setRemark(trimToNull(request.remark()));
    }

    private void validateReferences(QuotaTransaction transaction, String userId) {
        if (quotaMapper.selectById(transaction.getQuotaId(), userId) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "额度记录不存在或无权操作: " + transaction.getQuotaId());
        }
        if (transaction.getEnrollmentId() != null
                && enrollmentMapper.selectById(transaction.getEnrollmentId(), userId) == null) {
            throw new BizException(ErrorCode.BAD_REQUEST,
                    "报名记录不存在或无权操作: " + transaction.getEnrollmentId());
        }
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!ALLOWED_TYPE.contains(type)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "不支持的交易类型: " + value);
        }
        return type;
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

    private String generateNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_TIME_FORMAT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
