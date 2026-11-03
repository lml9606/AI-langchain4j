package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.RequireRole;
import com.example.langchain4j.common.auth.UserRole;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.guard.OutputGuardService;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.repository.entity.SensitiveWord;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 敏感词管理接入层：词表增删/启停（管理端操作，改后热更新输出审核缓存）。
 * <p>
 * 角色说明：词表为运营资产，整类标注 {@link RequireRole}(ADMIN)，
 * 由 RoleInterceptor 统一校验（普通用户 403）。
 */
@RequireRole(UserRole.ADMIN)
@RestController
@RequestMapping("/api/sensitive-words")
public class SensitiveWordController {

    private final OutputGuardService outputGuardService;

    public SensitiveWordController(OutputGuardService outputGuardService) {
        this.outputGuardService = outputGuardService;
    }

    /** 全量词表 */
    @GetMapping
    public ApiResponse<List<SensitiveWord>> list() {
        return ApiResponse.success(outputGuardService.listAll());
    }

    /** 新增敏感词（非法参数统一转业务错误码） */
    @PostMapping
    public ApiResponse<Map<String, Object>> add(@RequestBody Map<String, String> body) {
        try {
            long id = outputGuardService.addWord(body.get("word"));
            return ApiResponse.success(Map.of("id", id));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /** 启用/停用 */
    @PostMapping("/{id}/enabled")
    public ApiResponse<Void> setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        try {
            outputGuardService.setEnabled(id, enabled);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        return ApiResponse.success();
    }

    /** 删除敏感词 */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        outputGuardService.delete(id);
        return ApiResponse.success();
    }
}
