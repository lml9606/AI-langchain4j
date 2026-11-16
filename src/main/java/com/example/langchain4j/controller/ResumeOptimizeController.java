package com.example.langchain4j.controller;

import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.request.ResumeOptimizeRequest;
import com.example.langchain4j.dto.request.ResumeProfileRequest;
import com.example.langchain4j.dto.response.ResumeOptimizeResponse;
import com.example.langchain4j.dto.response.ResumeRevisionSummary;
import com.example.langchain4j.graph.resume.model.ResumePromptPresets;
import com.example.langchain4j.repository.entity.UserResume;
import com.example.langchain4j.service.ResumeOptimizeService;
import jakarta.validation.Valid;
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
 * 简历优化接入层：同步优化 / 简历档案 / 历史详情 / 历史列表 / 保存标记 / 预设提示词。
 * <p>
 * 所有接口都在 /api/** 下，由 JwtAuthFilter 统一认证；数据归属校验在 Service 层。
 */
@RestController
@RequestMapping("/api/resume")
public class ResumeOptimizeController {

    private final ResumeOptimizeService resumeOptimizeService;

    public ResumeOptimizeController(ResumeOptimizeService resumeOptimizeService) {
        this.resumeOptimizeService = resumeOptimizeService;
    }

    /**
     * 上传/更新简历档案（每用户仅一份，覆盖式 upsert；对话内触发优化时取该档案作为原文）。
     *
     * @param request 档案请求（title + content；content 为简历原文，不塞进对话消息表）
     * @return 统一响应（无数据）
     */
    @PostMapping("/profile")
    public ApiResponse<Void> saveProfile(@Valid @RequestBody ResumeProfileRequest request) {
        resumeOptimizeService.saveProfile(request.getTitle(), request.getContent());
        return ApiResponse.success();
    }

    /**
     * 查询当前用户简历档案。
     *
     * @return 档案内容（未上传返回 null）
     */
    @GetMapping("/profile")
    public ApiResponse<UserResume> profile() {
        return ApiResponse.success(resumeOptimizeService.getProfile());
    }

    /**
     * 执行一次简历优化（定向或自由）。
     *
     * @param request 优化请求（mode：TARGETED / FREE + 目标岗位等）
     * @return 优化结果（改稿 + 变更清单 + diff）
     */
    @PostMapping("/optimize")
    public ApiResponse<ResumeOptimizeResponse> optimize(
            @Valid @RequestBody ResumeOptimizeRequest request) {
        return ApiResponse.success(resumeOptimizeService.optimize(request));
    }

    /**
     * 查询优化详情。
     *
     * @param revisionId 优化记录 ID（业务 ID，同时是子图 threadId）
     * @return 优化详情（含原文 / 改稿 / diff）
     */
    @GetMapping("/revisions/{revisionId}")
    public ApiResponse<ResumeOptimizeResponse> revision(@PathVariable String revisionId) {
        return ApiResponse.success(resumeOptimizeService.getRevision(revisionId));
    }

    /**
     * 当前用户的历史优化记录列表。
     *
     * @param limit 返回条数（默认 20，上限 100）
     * @return 历史记录摘要列表
     */
    @GetMapping("/revisions")
    public ApiResponse<List<ResumeRevisionSummary>> revisions(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(resumeOptimizeService.listRevisions(limit));
    }

    /**
     * 用户确认保存（DONE → SAVED，幂等）。
     *
     * @param revisionId 优化记录 ID
     * @return 统一响应（无数据）
     */
    @PostMapping("/revisions/{revisionId}/save")
    public ApiResponse<Void> save(@PathVariable String revisionId) {
        resumeOptimizeService.markSaved(revisionId);
        return ApiResponse.success();
    }

    /**
     * 自由优化预设提示词。
     *
     * @return 预设提示词（5 类 × 5 条）
     */
    @GetMapping("/presets")
    public ApiResponse<Map<String, List<ResumePromptPresets.Preset>>> presets() {
        return ApiResponse.success(resumeOptimizeService.presets());
    }
}
