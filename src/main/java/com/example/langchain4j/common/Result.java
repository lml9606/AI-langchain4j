package com.example.langchain4j.common;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-30 17:46
 * @Modify:
 **/
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Result {
    private boolean success;
    private String message;
    private Object data;
}
