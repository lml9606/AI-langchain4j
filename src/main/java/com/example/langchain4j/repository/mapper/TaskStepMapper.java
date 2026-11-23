package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.TaskStep;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 任务步骤 Mapper：每步状态、结果和参数快照。
 */
@Mapper
public interface TaskStepMapper {

    int insertBatch(@Param("steps") List<TaskStep> steps);

    List<TaskStep> selectByRunId(@Param("runId") String runId);

    int markRunning(@Param("runId") String runId, @Param("stepNo") int stepNo);

    int updateArgs(@Param("runId") String runId,
                   @Param("stepNo") int stepNo,
                   @Param("argsJson") String argsJson);

    int markSucceeded(@Param("runId") String runId,
                      @Param("stepNo") int stepNo,
                      @Param("summary") String summary,
                      @Param("resultJson") String resultJson,
                      @Param("evidenceJson") String evidenceJson,
                      @Param("costMs") long costMs);

    int markFailed(@Param("runId") String runId,
                   @Param("stepNo") int stepNo,
                   @Param("errorCode") String errorCode,
                   @Param("errorMessage") String errorMessage,
                   @Param("costMs") long costMs);

    /** 前序失败后，把尚未执行的步骤统一标记跳过。 */
    int markSkipped(@Param("runId") String runId, @Param("fromStepNo") int fromStepNo);

    /** 失败重试时，把上一轮因前序失败而跳过的步骤恢复为待执行。 */
    int resetSkipped(@Param("runId") String runId);

    int markCancelledByRun(@Param("runId") String runId);

    /** 会话被新消息打断：取消该会话下所有已取消 run 的未完成步骤。 */
    int markCancelledByConversation(@Param("userId") String userId,
                                    @Param("conversationId") String conversationId);
}
