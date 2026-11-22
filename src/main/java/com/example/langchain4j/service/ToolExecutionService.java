package com.example.langchain4j.service;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.repository.mapper.ToolExecutionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.Optional;

/**
 * 工具执行台账服务：写工具幂等闸门与结果查询。
 *
 * <p>核心约束：台账写入必须与业务写入处在同一个数据库事务里（{@link Propagation#MANDATORY}）。
 * 这样只有两种可能：
 * <ul>
 *   <li>业务成功 + 台账 SUCCEEDED 一起提交；</li>
 *   <li>业务失败，台账 RUNNING 一起回滚，重试可以安全重跑。</li>
 * </ul>
 * 不存在"台账说成功、业务没改"或"业务改了、台账没记"的中间态。
 */
@Slf4j
@Service
public class ToolExecutionService {

    /** 幂等闸门结果 */
    public enum BeginStatus {
        /** 抢到执行权，可以执行 */
        ACQUIRED,
        /** 同一幂等键已经成功执行过，直接返回上次结果 */
        REPLAY,
        /** 同一幂等键正在被其他请求执行 */
        IN_PROGRESS,
        /** 同一幂等键对应了不同参数，拒绝复用 */
        CONFLICT
    }

    public record BeginResult(BeginStatus status, ToolExecution execution) {
    }

    private final ToolExecutionMapper executionMapper;
    private final AgentProperties properties;

    public ToolExecutionService(ToolExecutionMapper executionMapper, AgentProperties properties) {
        this.executionMapper = executionMapper;
        this.properties = properties;
    }

    /**
     * 创建待确认动作台账：用户看到确认卡片时，数据库里已经有 PENDING 记录。
     *
     * <p>调用顺序必须是 Redis trySet 成功后；若这里返回 false，调用方要撤销 Redis key，
     * 避免出现"有确认令牌、没有数据库记录"。
     *
     * @return true=写入成功；false=同一确认 ID 已存在
     */
    @Transactional
    public boolean recordPending(ToolExecution execution) {
        boolean inserted = executionMapper.insertPending(execution) == 1;
        if (inserted) {
            log.info("[tool-exec] pending recorded, executionTail={}, tool={}",
                    idTail(execution.getExecutionId()), execution.getTool());
        }
        return inserted;
    }

    /**
     * 幂等闸门：抢占执行权、命中重放或判定冲突。
     * <p>必须在业务事务内调用，返回值决定调用方是继续执行还是直接返回上次结果。
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BeginResult begin(ToolExecution execution) {
        if (executionMapper.insertRunning(execution) == 1) {
            log.info("[tool-exec] acquired, executionTail={}, tool={}",
                    idTail(execution.getExecutionId()), execution.getTool());
            return new BeginResult(BeginStatus.ACQUIRED, execution);
        }
        ToolExecution existing = executionMapper.selectByExecutionId(
                execution.getUserId(), execution.getExecutionId());
        if (existing == null) {
            // 唯一键冲突但查不到：并发事务尚未提交，交给调用方稍后重试
            return new BeginResult(BeginStatus.IN_PROGRESS, null);
        }
        // PENDING 是用户确认前写入的，尚未计算参数指纹；接管时再补全。
        if (existing.getArgsHash() != null
                && !Objects.equals(existing.getArgsHash(), execution.getArgsHash())) {
            log.warn("[tool-exec] args mismatch, executionTail={}, tool={}",
                    idTail(execution.getExecutionId()), execution.getTool());
            return new BeginResult(BeginStatus.CONFLICT, existing);
        }
        if (ToolExecution.STATUS_SUCCEEDED.equals(existing.getStatus())) {
            log.info("[tool-exec] replay, executionTail={}, tool={}",
                    idTail(execution.getExecutionId()), execution.getTool());
            return new BeginResult(BeginStatus.REPLAY, existing);
        }
        if (ToolExecution.STATUS_CANCELLED.equals(existing.getStatus())) {
            log.warn("[tool-exec] confirmation cancelled, executionTail={}, tool={}",
                    idTail(execution.getExecutionId()), execution.getTool());
            return new BeginResult(BeginStatus.CONFLICT, existing);
        }
        int staleSeconds = Math.max(1, properties.getTool().getConfirmation().getStaleSeconds());
        if (executionMapper.tryReclaim(execution.getUserId(), execution.getExecutionId(),
                staleSeconds, execution.getArgsHash()) == 1) {
            log.info("[tool-exec] reclaimed, executionTail={}, tool={}",
                    idTail(execution.getExecutionId()), execution.getTool());
            return new BeginResult(BeginStatus.ACQUIRED, existing);
        }
        return new BeginResult(BeginStatus.IN_PROGRESS, existing);
    }

    /** 标记成功并保存结果（重放时原样返回） */
    @Transactional(propagation = Propagation.MANDATORY)
    public void succeed(String userId, String executionId, String result) {
        executionMapper.markSucceeded(userId, executionId, result);
    }

    /** 标记失败：业务已回滚，允许后续重试接管 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void fail(String userId, String executionId, String errorCode, String errorMessage) {
        executionMapper.markFailed(userId, executionId, errorCode, errorMessage);
    }

    /** 用户取消确认：PENDING/FAILED 流转为 CANCELLED；已经成功的记录不会被覆盖。 */
    @Transactional
    public void markCancelled(String userId, String executionId) {
        int changed = executionMapper.markCancelled(userId, executionId);
        if (changed == 0) {
            log.info("[tool-exec] cancel skipped (not cancellable), executionTail={}",
                    idTail(executionId));
        }
    }

    /** 查询执行结果（令牌已删除时，用它回答"到底执行没执行"） */
    public Optional<ToolExecution> find(String userId, String executionId) {
        return Optional.ofNullable(executionMapper.selectByExecutionId(userId, executionId));
    }

    private String idTail(String id) {
        if (id == null || id.isBlank()) {
            return "";
        }
        return id.length() <= 8 ? "***" : id.substring(id.length() - 8);
    }
}
