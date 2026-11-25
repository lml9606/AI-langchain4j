package com.example.langchain4j.tool.run;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 多步写运行台账 Mapper：全部查询强制带 user_id。
 */
@Mapper
public interface TaskRunMapper {

    /** 创建 PENDING 运行；run_id 冲突返回 0。 */
    int insertPending(TaskRun run);

    TaskRun selectByRunId(@Param("userId") String userId, @Param("runId") String runId);

    /**
     * 抢占执行权：PENDING/FAILED/PARTIAL_FAILED 直接接管；
     * RUNNING 超过 staleSeconds 视为进程崩溃留下的僵尸运行，也允许接管。
     */
    int markRunning(@Param("userId") String userId, @Param("runId") String runId,
                    @Param("staleSeconds") int staleSeconds);

    int markSucceeded(@Param("userId") String userId, @Param("runId") String runId,
                      @Param("result") String result);

    /** 失败时记录失败步骤、错误与已完成进度（前面成功步骤不会回滚）。 */
    int markFailed(@Param("userId") String userId, @Param("runId") String runId,
                   @Param("status") String status, @Param("currentStep") int currentStep,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("result") String result);

    int markCancelled(@Param("userId") String userId, @Param("runId") String runId);

    /** 新消息放弃旧确认：按会话取消仍 PENDING 的运行。 */
    int markCancelledByConversation(@Param("userId") String userId,
                                    @Param("conversationId") String conversationId);
}
