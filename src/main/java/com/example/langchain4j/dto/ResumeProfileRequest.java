package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 简历档案上传/更新入参（每用户仅一份，覆盖式）。
 */
@Data
public class ResumeProfileRequest {

    @Size(max = 200, message = "简历标题不能超过 200 字")
    private String title;

    @NotBlank(message = "简历内容不能为空")
    @Size(max = 20_000, message = "简历内容不能超过 20000 字")
    private String content;
}
