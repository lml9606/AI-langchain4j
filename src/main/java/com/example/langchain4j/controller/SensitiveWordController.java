package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.RequireRole;
import com.example.langchain4j.common.auth.UserRole;
import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.guard.OutputGuardService;
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
 *
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

    /**
     * 全量敏感词表。
     *
     * @return 词表列表（含启用状态）
     */
    @GetMapping
    public ApiResponse<List<SensitiveWord>> list() {
        return ApiResponse.success(outputGuardService.listAll());
    }

    /**
     * 新增敏感词（改后热更新输出审核缓存）。
     *
     * @param body 含 word 字段（空/重复时转 BAD_REQUEST 业务码）
     * @return 统一响应包裹的新词 id
     */
    @PostMapping
    public ApiResponse<Map<String, Object>> add(@RequestBody Map<String, String> body) {
        try {
            long id = outputGuardService.addWord(body.get("word"));
            return ApiResponse.success(Map.of("id", id));
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
    }

    /**
     * 启用/停用敏感词（改后热更新输出审核缓存）。
     *
     * @param id      敏感词 id
     * @param enabled true = 启用 / false = 停用
     * @return 统一响应（无数据）
     */
    @PostMapping("/{id}/enabled")
    public ApiResponse<Void> setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        try {
            outputGuardService.setEnabled(id, enabled);
        } catch (IllegalArgumentException e) {
            throw new BizException(ErrorCode.BAD_REQUEST, e.getMessage());
        }
        return ApiResponse.success();
    }

    /**
     * 删除敏感词（改后热更新输出审核缓存）。
     *
     * @param id 敏感词 id
     * @return 统一响应（无数据）
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        outputGuardService.delete(id);
        return ApiResponse.success();
    }
}
