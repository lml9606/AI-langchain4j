package com.example.langchain4j.controller;

import com.example.langchain4j.service.PromptGeneratorService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-30 17:48
 * @Modify:
 **/
@RestController
@RequestMapping("/prompt")
@Slf4j
public class PromptController {

    @Resource
    private PromptGeneratorService promptGeneratorService;

    @RequestMapping("/generate")
    public String generate(String message){

        return promptGeneratorService.generatePrompt(message);
    }
}
