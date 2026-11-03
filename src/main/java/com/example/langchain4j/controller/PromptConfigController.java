package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.RequireRole;
import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.auth.UserRole;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.PromptSaveRequest;
import com.example.langchain4j.repository.entity.PromptConfig;
import com.example.langchain4j.service.PromptTemplateService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Prompt 管理接入层：查看/保存/回退提示词（保存后热生效，version 递增审计）。
 * <p>
 * 角色说明：查看对全员开放；覆盖（保存/删除）标注 {@link RequireRole}(ADMIN)，
 * 由 RoleInterceptor 统一校验（普通用户 403）。
 */
@RestController
@RequestMapping("/api/prompts")
public class PromptConfigController {

    private final PromptTemplateService promptTemplateService;

    public PromptConfigController(PromptTemplateService promptTemplateService) {
        this.promptTemplateService = promptTemplateService;
    }

    /** 全部 key 配置概览（含生效文本、版本；version=0 表示未覆盖、用代码默认值） */
    @GetMapping
    public ApiResponse<List<PromptConfig>> list() {
        return ApiResponse.success(promptTemplateService.listAll());
    }

    /** 保存/覆盖提示词（热生效；仅管理员） */
    @RequireRole(UserRole.ADMIN)
    @PostMapping
    public ApiResponse<PromptConfig> save(@Valid @RequestBody PromptSaveRequest request) {
        return ApiResponse.success(promptTemplateService.save(
                request.key(), request.text(), UserContext.require()));
    }

    /** 删除覆盖 → 回退代码默认值（仅管理员） */
    @RequireRole(UserRole.ADMIN)
    @DeleteMapping("/{key}")
    public ApiResponse<Void> delete(@PathVariable String key) {
        promptTemplateService.delete(key);
        return ApiResponse.success();
    }
}
