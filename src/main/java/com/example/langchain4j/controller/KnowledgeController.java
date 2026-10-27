package com.example.langchain4j.controller;

import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.KnowledgeImportRequest;
import com.example.langchain4j.dto.KnowledgeSearchResponse;
import com.example.langchain4j.service.KnowledgeService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 知识接入层：导入（嵌入 + 落库）与语义检索。
 */
@Validated
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final KnowledgeService knowledgeService;

    public KnowledgeController(KnowledgeService knowledgeService) {
        this.knowledgeService = knowledgeService;
    }

    /** 知识导入：单条文本嵌入后写入 pgvector */
    @PostMapping("/import")
    public ApiResponse<Map<String, Object>> importKnowledge(@Valid @RequestBody KnowledgeImportRequest request) {
        long id = knowledgeService.importChunk(request);
        return ApiResponse.success(Map.of("id", id));
    }

    /** 语义检索：query 嵌入后余弦相似度检索 topK */
    @GetMapping("/search")
    public ApiResponse<KnowledgeSearchResponse> search(
            @RequestParam @NotBlank(message = "query 不能为空") String query,
            @RequestParam(defaultValue = "3") int topK) {
        return ApiResponse.success(knowledgeService.search(query, topK));
    }
}
