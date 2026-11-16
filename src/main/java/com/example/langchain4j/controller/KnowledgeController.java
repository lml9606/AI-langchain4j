package com.example.langchain4j.controller;

import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.request.KnowledgeBatchImportRequest;
import com.example.langchain4j.dto.request.KnowledgeUpdateRequest;
import com.example.langchain4j.dto.response.KnowledgeBatchImportResponse;
import com.example.langchain4j.dto.response.KnowledgeHealthResponse;
import com.example.langchain4j.dto.response.KnowledgeSearchResponse;
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

    /**
     * 语义检索：query 嵌入后余弦相似度检索 topK。
     *
     * @param query 检索词（必填非空；混合检索双通道：向量 + 词面）
     * @param topK  返回条数（默认 3）
     * @return 检索结果（含相似度得分与命中内容）
     */
    @GetMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(
            @RequestParam @NotBlank(message = "query 不能为空") String query,
            @RequestParam(defaultValue = "3") int topK) {
        return ApiResponse.success(knowledgeService.search(query, topK));
    }

    /**
     * 批量导入：多篇文档逐篇父子分块落库（父块整章不嵌入 + 子块嵌入；子块参数逐篇可配）。
     * <p>
     * 特殊条件：单篇失败不中断整批。
     *
     * @param request 批量导入请求（文档列表 + 分块参数）
     * @return 逐篇导入结果，供重试失败项
     */
    @PostMapping("/batch")
    public ApiResponse<KnowledgeBatchImportResponse> batchImport(
            @Valid @RequestBody KnowledgeBatchImportRequest request) {
        return ApiResponse.success(knowledgeService.batchImport(request));
    }

    /**
     * 更新知识块：内容变更后重嵌向量。
     * <p>
     * 特殊条件：仅限本人私有块，公共库块 403。
     *
     * @param id      知识块 id
     * @param request 新内容（更新后重新嵌入）
     * @return 统一响应（无数据）
     */
    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id,
                                    @Valid @RequestBody KnowledgeUpdateRequest request) {
        knowledgeService.updateChunk(id, request);
        return ApiResponse.success();
    }

    /**
     * 删除知识块。
     * <p>
     * 特殊条件：仅限本人私有块，公共库块 403。
     *
     * @param id 知识块 id
     * @return 统一响应（无数据）
     */
    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable long id) {
        knowledgeService.deleteChunk(id);
        return ApiResponse.success();
    }

    /**
     * 知识库健康度：可见范围（公共库 + 本人私有库）聚合统计。
     *
     * @return 健康度聚合结果（异常块驱动知识治理）
     */
    @GetMapping("/health")
    public ApiResponse<KnowledgeHealthResponse> health() {
        return ApiResponse.success(knowledgeService.health());
    }
}
