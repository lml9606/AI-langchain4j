package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.dto.request.EnrollmentRequest;
import com.example.langchain4j.repository.entity.Enrollment;
import com.example.langchain4j.repository.mapper.EnrollmentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 报名业务服务：创建、查询、更新和删除当前用户的报名记录。
 *
 * 所有按 ID 的操作都以 user_id 作为 SQL 条件，避免通过猜测 ID 读取或修改他人数据。
 */
@Slf4j
@Service
public class EnrollmentService {

    private static final int MAX_PAGE_SIZE = 100;
    private static final DateTimeFormatter NO_TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss");
    private final EnrollmentMapper enrollmentMapper;

    public EnrollmentService(EnrollmentMapper enrollmentMapper) {
        this.enrollmentMapper = enrollmentMapper;
    }

    /** 创建报名记录；报名单号由服务端生成，客户端不能指定归属用户。 */
    @Transactional(rollbackFor = Exception.class)
    public Enrollment create(String userId, EnrollmentRequest request) {
        Enrollment enrollment = new Enrollment();
        enrollment.setEnrollmentNo(generateNo("EN"));
        enrollment.setUserId(userId);
        apply(enrollment, request);
        enrollmentMapper.insert(enrollment);
        log.info("[enrollment] created, id={}, no={}, user={}",
                enrollment.getId(), enrollment.getEnrollmentNo(), userId);
        return requireOwned(enrollment.getId(), userId);
    }

    /** 按 ID 查询当前用户的报名记录；不存在和越权统一返回 404。 */
    public Enrollment get(long id, String userId) {
        return requireOwned(id, userId);
    }

    /** 查询当前用户的报名列表。 */
    public List<Enrollment> list(String userId, int limit) {
        List<Enrollment> list = enrollmentMapper.selectByUserId(userId, safeLimit(limit));
        return list == null ? List.of() : list;
    }

    /** 全量更新报名记录；只更新当前用户自己的数据。 */
    @Transactional(rollbackFor = Exception.class)
    public Enrollment update(long id, String userId, EnrollmentRequest request) {
        Enrollment enrollment = requireOwned(id, userId);
        apply(enrollment, request);
        if (enrollmentMapper.update(enrollment) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "报名记录不存在或无权操作: " + id);
        }
        log.info("[enrollment] updated, id={}, user={}", id, userId);
        return requireOwned(id, userId);
    }

    /** 删除当前用户的报名记录；返回是否实际删除。 */
    @Transactional(rollbackFor = Exception.class)
    public void delete(long id, String userId) {
        if (enrollmentMapper.delete(id, userId) == 0) {
            throw new BizException(ErrorCode.NOT_FOUND, "报名记录不存在或无权操作: " + id);
        }
        log.info("[enrollment] deleted, id={}, user={}", id, userId);
    }

    private Enrollment requireOwned(long id, String userId) {
        Enrollment enrollment = enrollmentMapper.selectById(id, userId);
        if (enrollment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报名记录不存在或无权操作: " + id);
        }
        return enrollment;
    }

    private void apply(Enrollment enrollment, EnrollmentRequest request) {
        BigDecimal amount = request.amount() == null ? BigDecimal.ZERO : request.amount();
        if (amount.signum() < 0) {
            throw new BizException(ErrorCode.BAD_REQUEST, "报名金额不能小于 0");
        }
        enrollment.setApplicantName(request.applicantName().trim());
        enrollment.setPhone(trimToNull(request.phone()));
        enrollment.setAmount(amount);
        enrollment.setRemark(trimToNull(request.remark()));
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
