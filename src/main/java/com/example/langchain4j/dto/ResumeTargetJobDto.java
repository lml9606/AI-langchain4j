package com.example.langchain4j.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

/**
 * 目标岗位入参：定向优化时由前端传入。
 */
@Data
public class ResumeTargetJobDto {

    @Size(max = 200, message = "职位名不能超过 200 字")
    private String jobName;

    @Size(max = 200, message = "公司名不能超过 200 字")
    private String company;

    @Size(max = 100, message = "薪资描述不能超过 100 字")
    private String salary;

    @Size(max = 8_000, message = "JD 不能超过 8000 字")
    private String jd;

    @Size(max = 50, message = "技能标签最多 50 个")
    private List<String> skills;

    @Size(max = 50, message = "岗位标签最多 50 个")
    private List<String> labels;
}
