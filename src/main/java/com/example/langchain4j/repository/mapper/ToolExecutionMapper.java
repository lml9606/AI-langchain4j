package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.ToolExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 工具执行台账 Mapper：幂等闸门、状态流转与结果查询。
 */
@Mapper
public interface ToolExecutionMapper {

    /**
     * 抢占执行权：唯一键冲突时返回 0（调用方据此判定"已执行过/正在执行"）。
     * <p>必须与业务写入在同一事务中，才能保证台账与业务数据同生共死。
     */
    int insertRunning(ToolExecution execution);

    /**
     * 创建待确认动作：写入 PENDING 状态与计划快照。
     * <p>唯一键冲突返回 0，说明同一确认 ID 已存在。
     */
    int insertPending(ToolExecution execution);

    ToolExecution selectByExecutionId(@Param("userId") String userId,
                                      @Param("executionId") String executionId);

    int markSucceeded(@Param("userId") String userId,
                      @Param("executionId") String executionId,
                      @Param("result") String result);

    int markFailed(@Param("userId") String userId,
                   @Param("executionId") String executionId,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage);

    /** 用户取消确认：只允许从待确认/失败等未成功状态流转。 */
    int markCancelled(@Param("userId") String userId,
                      @Param("executionId") String executionId);

    /**
     * 接管待确认/失败/失联的执行：PENDING、FAILED 直接允许重试；
     * RUNNING 超过 staleSeconds 视为进程崩溃。
     *
     * @return 1 = 接管成功，可以重新执行；0 = 仍在执行中
     */
    int tryReclaim(@Param("userId") String userId,
                   @Param("executionId") String executionId,
                   @Param("staleSeconds") int staleSeconds,
                   @Param("argsHash") String argsHash);
}
