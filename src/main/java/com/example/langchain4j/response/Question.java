package com.example.langchain4j.response;

import lombok.Data;

import java.util.List;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 15:05
 * @Modify:
 **/
@Data
public class Question {
    private int id;
    private String question;
    private List<String> options;
    private String defaultOption;
}
