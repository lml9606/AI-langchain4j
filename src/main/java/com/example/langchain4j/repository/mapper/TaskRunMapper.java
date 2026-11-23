package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.TaskRun;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 任务实例 Mapper：状态机流转、游标推进和执行租约。
 */
@Mapper
public interface TaskRunMapper {

    int insert(TaskRun run);

    TaskRun selectById(@Param("userId") String userId, @Param("id") String id);

    TaskRun selectByConfirmationId(@Param("userId") String userId,
                                   @Param("confirmationId") String confirmationId);

    /** 写步骤已完成只读前置步骤，进入等待用户确认。 */
    int markWaitingConfirm(@Param("userId") String userId,
                           @Param("id") String id,
                           @Param("confirmationId") String confirmationId,
                           @Param("planHash") String planHash);

    /** 用户确认后进入执行中；允许从 PLANNING / WAITING_CONFIRM / FAILED 重新进入。 */
    int markRunning(@Param("userId") String userId,
                    @Param("id") String id,
                    @Param("confirmationId") String confirmationId);

    int updateStatus(@Param("userId") String userId,
                     @Param("id") String id,
                     @Param("status") String status,
                     @Param("currentStep") int currentStep,
                     @Param("errorCode") String errorCode,
                     @Param("errorMessage") String errorMessage);

    int advanceCursor(@Param("userId") String userId,
                      @Param("id") String id,
                      @Param("currentStep") int currentStep);

    int saveResult(@Param("userId") String userId,
                   @Param("id") String id,
                   @Param("result") String result);

    /** 抢占执行租约；返回 1 表示当前实例获得执行权。 */
    int acquireLease(@Param("id") String id,
                     @Param("owner") String owner,
                     @Param("leaseSeconds") int leaseSeconds);

    /** 续租：长任务每推进一步前调用，避免执行中途租约过期被其他实例接管。 */
    int renewLease(@Param("id") String id,
                   @Param("owner") String owner,
                   @Param("leaseSeconds") int leaseSeconds);

    int releaseLease(@Param("id") String id, @Param("owner") String owner);

    /** 崩溃恢复：执行中但租约已过期（或从未写入租约）的任务。 */
    List<TaskRun> findLeaseExpired(@Param("limit") int limit);

    /** 取消一条尚未进入终态的任务；返回 1 表示本次确实完成流转。 */
    int markCancelled(@Param("userId") String userId, @Param("id") String id);

    int markCancelledByConversation(@Param("userId") String userId,
                                    @Param("conversationId") String conversationId);
}
