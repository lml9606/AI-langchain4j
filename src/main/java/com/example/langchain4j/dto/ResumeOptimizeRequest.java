package com.example.langchain4j.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 简历优化入参。
 * <p>
 * 定向模式：{@code mode=TARGETED} + {@code targets} 必填；
 * 自由模式：{@code mode=FREE} + {@code optimizationPrompt} 必填，
 * {@code selectedSections} 为空表示全部章节。
 */
@Data
public class ResumeOptimizeRequest {

    @NotBlank(message = "简历原文不能为空")
    @Size(max = 20_000, message = "简历原文不能超过 20000 字")
    private String resumeText;

    @Size(max = 200, message = "简历标题不能超过 200 字")
    private String resumeTitle;

    @NotBlank(message = "优化模式不能为空")
    private String mode;

    /** 触发会话 ID（可空）：非空时优化锁会话（同会话串行）；对话内触发的记录由此回写完成通知 */
    @Size(max = 64, message = "会话 ID 不能超过 64 字")
    private String conversationId;

    /** 模型名；为空使用 agent.llm.default-model */
    private String model;

    @Valid
    @Size(max = 10, message = "目标岗位最多 10 个")
    private List<ResumeTargetJobDto> targets;

    @Size(max = 50, message = "选定章节最多 50 个")
    private List<String> selectedSections;

    @Size(max = 2_000, message = "优化提示词不能超过 2000 字")
    private String optimizationPrompt;
}
