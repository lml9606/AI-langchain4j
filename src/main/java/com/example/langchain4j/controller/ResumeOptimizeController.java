package com.example.langchain4j.controller;

import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.ResumeOptimizeRequest;
import com.example.langchain4j.dto.ResumeOptimizeResponse;
import com.example.langchain4j.dto.ResumeRevisionSummary;
import com.example.langchain4j.graph.resume.model.ResumePromptPresets;
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
 * 简历优化接入层：同步优化 / 历史详情 / 历史列表 / 保存标记 / 预设提示词。
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

    /** 执行一次简历优化（定向或自由） */
    @PostMapping("/optimize")
    public ApiResponse<ResumeOptimizeResponse> optimize(
            @Valid @RequestBody ResumeOptimizeRequest request) {
        return ApiResponse.success(resumeOptimizeService.optimize(request));
    }

    /** 查询优化详情（含原文/改稿/diff） */
    @GetMapping("/revisions/{revisionId}")
    public ApiResponse<ResumeOptimizeResponse> revision(@PathVariable String revisionId) {
        return ApiResponse.success(resumeOptimizeService.getRevision(revisionId));
    }

    /** 当前用户的历史记录（默认 20 条，上限 100） */
    @GetMapping("/revisions")
    public ApiResponse<List<ResumeRevisionSummary>> revisions(
            @RequestParam(defaultValue = "20") int limit) {
        return ApiResponse.success(resumeOptimizeService.listRevisions(limit));
    }

    /** 用户确认保存（DONE → SAVED，幂等） */
    @PostMapping("/revisions/{revisionId}/save")
    public ApiResponse<Void> save(@PathVariable String revisionId) {
        resumeOptimizeService.markSaved(revisionId);
        return ApiResponse.success();
    }

    /** 自由优化预设提示词（5 类 × 5 条） */
    @GetMapping("/presets")
    public ApiResponse<Map<String, List<ResumePromptPresets.Preset>>> presets() {
        return ApiResponse.success(resumeOptimizeService.presets());
    }
}
