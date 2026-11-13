package com.example.langchain4j.controller;

import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.KnowledgeBatchImportRequest;
import com.example.langchain4j.dto.KnowledgeBatchImportResponse;
import com.example.langchain4j.dto.KnowledgeHealthResponse;
import com.example.langchain4j.dto.KnowledgeSearchResponse;
import com.example.langchain4j.dto.KnowledgeUpdateRequest;
import com.example.langchain4j.service.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 知识接入层：父子分块导入（批量）、语义检索与知识治理
 * （更新/删除/健康度；更新删除仅限本人私有块，公共库由运营侧维护）。
 */
@Validated
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 语义检索：query 嵌入后余弦相似度检索 topK */
    @GetMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(
            @RequestParam @NotBlank(message = "query 不能为空") String query,
            @RequestParam(defaultValue = "3") int topK) {
        return ApiResponse.success(knowledgeService.search(query, topK));
    }

    /**
     * 批量导入：多篇文档逐篇父子分块落库（父块整章不嵌入 + 子块嵌入；子块参数逐篇可配），
     * 单篇失败不中断整批，返回逐条结果供重试失败项。
     */
    @PostMapping("/batch")
    public ApiResponse<KnowledgeBatchImportResponse> batchImport(
            @Valid @RequestBody KnowledgeBatchImportRequest request) {
        return ApiResponse.success(knowledgeService.batchImport(request));
    }

    /** 更新知识块：内容变更后重嵌向量（仅本人私有块；公共库块 403） */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id,
                                    @Valid @RequestBody KnowledgeUpdateRequest request) {
        knowledgeService.updateChunk(id, request);
        return ApiResponse.success();
    }

    /** 删除知识块（仅本人私有块；公共库块 403） */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        knowledgeService.deleteChunk(id);
        return ApiResponse.success();
    }

    /** 知识库健康度：可见范围（公共库 + 本人私有库）聚合统计，异常块驱动治理 */
    @GetMapping("/health")
    public ApiResponse<KnowledgeHealthResponse> health() {
        return ApiResponse.success(knowledgeService.health());
    }
}
