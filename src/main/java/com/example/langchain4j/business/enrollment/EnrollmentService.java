package com.example.langchain4j.business.enrollment;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.format.DateTimeFormatter;
import java.time.LocalDateTime;
import java.util.concurrent.ThreadLocalRandom;
import java.util.List;

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

    /** 注入报名 Mapper；所有读写都通过 Mapper 的 user_id 条件做归属隔离。 */
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

    /** 按报名单号查询当前用户的报名记录；不存在返回 null，不泄露其他用户的数据。 */
    public Enrollment findByNo(String userId, String enrollmentNo) {
        if (enrollmentNo == null || enrollmentNo.isBlank()) {
            return null;
        }
        return enrollmentMapper.selectByEnrollmentNo(enrollmentNo.trim(), userId);
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

    /** 按 ID + 用户查询报名；查不到和越权统一转 404，避免泄露记录是否存在。 */
    private Enrollment requireOwned(long id, String userId) {
        Enrollment enrollment = enrollmentMapper.selectById(id, userId);
        if (enrollment == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "报名记录不存在或无权操作: " + id);
        }
        return enrollment;
    }

    /** 把请求字段写入实体；金额缺省为 0，负数在落库前拒绝。 */
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

    /** 生成带前缀、时间戳和随机后缀的业务单号；不承担数据库唯一性约束。 */
    private String generateNo(String prefix) {
        return prefix + LocalDateTime.now().format(NO_TIME_FORMAT)
                + String.format("%06d", ThreadLocalRandom.current().nextInt(1_000_000));
    }
}
