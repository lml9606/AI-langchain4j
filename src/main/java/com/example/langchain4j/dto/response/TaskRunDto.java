package com.example.langchain4j.dto.response;

import java.util.List;
import java.util.Map;

/**
 * 多步骤任务进度视图：run 级状态 + 每一步的执行结果。
 */
public record TaskRunDto(String runId,
                         String conversationId,
                         String workflowId,
                         String status,
                         int currentStep,
                         String confirmationId,
                         String errorCode,
                         String errorMessage,
                         String result,
                         List<Step> steps) {

    /** 单个步骤视图：args 是解析后的参数快照。 */
    public record Step(int stepNo,
                       String stageId,
                       String tool,
                       String sideEffect,
                       Map<String, Object> args,
                       String status,
                       String summary,
                       String errorCode,
                       String errorMessage,
                       long costMs,
                       int attempts) {
    }
}
