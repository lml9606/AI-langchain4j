package com.example.langchain4j.request;


import com.example.langchain4j.common.Result;
import lombok.Data;

import java.util.Map;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 14:39
 * @Modify:
 **/

@Data
public class GenerateRequest {

    private String sessionId;             // 可选，新会话不传
    private String userInput;             // 用户输入（一句话或对选择题的回答）
    private Integer selectedOptionId;          // 选择的选项编号
}
