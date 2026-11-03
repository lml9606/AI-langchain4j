package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 评测用例新增请求。
 */
public record EvalCaseRequest(
        @NotBlank(message = "question 不能为空")
        @Size(max = 1000, message = "question 长度不能超过 1000")
        String question,

        /** 期望关键词（召回评测：按其在检索上下文中的命中比例打分；可空表示跳过召回打分） */
        List<String> keywords,

        /** 参考答案（忠实度评测参考，可空） */
        @Size(max = 2000, message = "expectedAnswer 长度不能超过 2000")
        String expectedAnswer) {
}
