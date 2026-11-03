package com.example.langchain4j.dto;

import java.util.List;

/**
 * 批量导入结果：逐条明细（index 对应请求 items 下标），失败项带错误信息供重试。
 */
public record KnowledgeBatchImportResponse(
        int total,
        int imported,
        int failed,
        List<ItemResult> results) {

    public record ItemResult(
            int index,
            /** 该文档切分落库的块数（失败为 0） */
            int chunks,
            boolean ok,
            /** 失败原因（成功为空） */
            String error) {
    }
}
