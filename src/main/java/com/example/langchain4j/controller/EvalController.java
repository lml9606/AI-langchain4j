package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.RequireRole;
import com.example.langchain4j.common.auth.UserRole;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.EvalCaseRequest;
import com.example.langchain4j.dto.EvalReportResponse;
import com.example.langchain4j.repository.entity.EvalCase;
import com.example.langchain4j.service.EvalService;
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
 * 评测接入层：用例管理 + 离线评测执行 + 报告查询。
 * <p>
 * 用途：知识库变更（导入/切块/检索参数）后跑一次评测，对比召回率与忠实度回归；
 * runEval 同步执行（用例逐条走完整检索 + LLM 链路），用例多时可改为异步任务。
 * 角色说明：启用/停用用例属于管理动作，标注 {@link RequireRole}(ADMIN) 由拦截器校验。
 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;

    public EvalController(EvalService evalService) {
        this.evalService = evalService;
    }

    /** 新增评测用例 */
    @PostMapping("/cases")
    public ApiResponse<Map<String, Object>> addCase(@Valid @RequestBody EvalCaseRequest request) {
        return ApiResponse.success(Map.of("id", evalService.addCase(request)));
    }

    /** 列出全部用例 */
    @GetMapping("/cases")
    public ApiResponse<List<EvalCase>> listCases() {
        return ApiResponse.success(evalService.listCases());
    }

    /** 启用/停用用例（仅管理员） */
    @RequireRole(UserRole.ADMIN)
    @PostMapping("/cases/{id}/enabled")
    public ApiResponse<Void> setEnabled(@PathVariable long id, @RequestParam boolean enabled) {
        evalService.setCaseEnabled(id, enabled);
        return ApiResponse.success();
    }

    /** 执行评测（同步，返回本批报告） */
    @PostMapping("/run")
    public ApiResponse<EvalReportResponse> run() {
        return ApiResponse.success(evalService.runEval());
    }

    /** 查询报告：默认最近一批；指定 runId 查历史批次 */
    @GetMapping("/report")
    public ApiResponse<EvalReportResponse> report(
            @RequestParam(required = false) String runId) {
        if (runId == null || runId.isBlank()) {
            return ApiResponse.success(evalService.latestReport());
        }
        return ApiResponse.success(evalService.report(runId));
    }
}
