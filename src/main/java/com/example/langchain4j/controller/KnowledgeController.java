package com.example.langchain4j.controller;

import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.request.KnowledgeBatchImportRequest;
import com.example.langchain4j.dto.request.KnowledgeUpdateRequest;
import com.example.langchain4j.dto.response.KnowledgeBatchImportResponse;
import com.example.langchain4j.dto.response.KnowledgeFileImportResponse;
import com.example.langchain4j.dto.response.KnowledgeHealthResponse;
import com.example.langchain4j.dto.response.KnowledgeSearchResponse;
import com.example.langchain4j.service.KnowledgeService;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

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
     * 文件上传导入：multipart 上传 PDF/Word/Markdown/txt，服务端解析文本后走父子分块落库。
     *
     * 特殊条件：单文件失败不中断整批（逐文件结果供重试失败项）；块归属当前登录用户（私有库）；
     * 单文件大小上限见 spring.servlet.multipart.max-file-size 配置。
     *
     * 文档说明：OpenAPI 将参数覆写描述为单文件 binary（Knife4j 4.4.0 不渲染 array 形式
     * 表单参数，原生推断的 List 会导致调试页无文件选择框）；实际接口支持同名 files 参数重复传多个文件。
     *
     * @param files 上传文件列表（支持多选；单次数量上限同 agent.knowledge.batch-max-documents）
     * @param documentId 逻辑文档 ID（可空；单文件重导同一文档时传入，用于版本更新）
     * @param version    文档版本（可空；空则自动取当前版本 + 1；新版本写完才切换，导入中对读不可见）
     * @return 逐文件导入结果（fileName / chunks / ok / error）
     */
    @Operation(summary = "文件上传导入知识库并父子分块落库")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponse<KnowledgeFileImportResponse> upload(
            @RequestPart("files") List<MultipartFile> files,
            @RequestParam(required = false) String documentId,
            @RequestParam(required = false) Integer version) {
        return ApiResponse.success(knowledgeService.uploadFiles(files, documentId, version));
    }

    /**
     * 更新知识块：内容变更后重嵌向量。
     *
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
     *
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
