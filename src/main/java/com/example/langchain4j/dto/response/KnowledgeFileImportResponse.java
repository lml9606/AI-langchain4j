package com.example.langchain4j.dto.response;

import java.util.List;

/**
 * 文件上传导入结果：逐文件明细（index 对应上传文件顺序），失败项带错误信息供重试。
 */
public record KnowledgeFileImportResponse(
        int total,
        int imported,
        int failed,
        List<FileResult> results) {

    public record FileResult(
            int index,
            /** 原始文件名（展示/溯源用） */
            String fileName,
            /** 解析+切分落库的块数（父块 + 子块；失败为 0） */
            int chunks,
            boolean ok,
            /** 失败原因（成功为空） */
            String error) {
    }
}
