package com.example.langchain4j.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 知识块更新请求：块级更新（内容变更后重嵌向量）。
 * 仅本人私有块可更新；公共库块由运营侧维护（403）。
 */
@Data
public class KnowledgeUpdateRequest {

    @NotBlank(message = "content 不能为空")
    @Size(max = 20000, message = "content 长度不能超过 20000")
    private String content;

    /** 元数据（整体替换），可空 */
    @Size(max = 2000, message = "metadata 长度不能超过 2000")
    private String metadata;
}
