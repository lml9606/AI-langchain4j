package com.example.langchain4j.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.Map;

/**
 * 评测用例新增请求。
 */
public record EvalCaseRequest(
        /** 场景：knowledge_qa（默认）/ task */
        String scenario,

        @NotBlank(message = "question 不能为空")
        @Size(max = 1000, message = "question 长度不能超过 1000")
        String question,

        /** 期望关键词（召回评测：按其在检索上下文中的命中比例打分；可空表示跳过召回打分） */
        List<String> keywords,

        /** 参考答案（忠实度评测参考，可空） */
        @Size(max = 2000, message = "expectedAnswer 长度不能超过 2000")
        String expectedAnswer,

        /** 期望工具（任务场景：如 query_business_data；可空表示不校验） */
        List<String> expectedTools,

        /** 期望参数（任务场景：tool -> args；可空表示不校验） */
        Map<String, Map<String, Object>> expectedArgs,

        /** 期望是否缺参追问（任务场景：true/false；null 表示不校验） */
        Boolean expectClarify) {
}
