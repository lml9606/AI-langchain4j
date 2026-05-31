package com.example.langchain4j.response;

import com.example.langchain4j.common.Result;

import java.util.List;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 14:42
 * @Modify:
 **/
public class GenerateResponse extends Result {
    private String status;                // NEED_CONFIRMATION / COMPLETED
    private String sessionId;
    private List<Question> questions;     // 选择题列表
    private String draftPrompt;           // 草案（待确认时）
    private List<String> assumptions;     // 假设声明
    private String systemPrompt;          // 最终提示词（完成时）
}