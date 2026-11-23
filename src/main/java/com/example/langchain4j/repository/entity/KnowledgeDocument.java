package com.example.langchain4j.repository.entity;

import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 文档主表实体（文档聚合根）。
 *
 * {@code activeVersion} 是"当前对外可读版本"的唯一事实来源：检索按它过滤，
 * 导入过程只写新版本块、不改这一列，发布时再原子切换。因此：
 *
 * - 导入中的新版本对读完全不可见，不会出现"查到半成品"；
 * - 切换只更新一行，成本与文档块数无关；
 * - 旧版本可在保留期内回滚，之后由 GC 回收。
 *
 */
@Data
public class KnowledgeDocument {

    private Long id;
    /** 逻辑文档 ID（与 ai_knowledge_chunk.document_id 对应） */
    private String documentId;
    /** 归属用户 */
    private String userId;
    /** 当前对外可读版本；0 表示尚未成功导入过（新文档导入期间不可见） */
    private Integer activeVersion;
    /** 导入状态：READY / IMPORTING / FAILED */
    private String status;
    /** 内容指纹（SHA-256），用于识别无变更重传 */
    private String contentHash;
    /** 来源（文件名等），便于运维定位 */
    private String source;
    /** 最近一次成功发布时间（旧版本保留窗口起点） */
    private OffsetDateTime activatedAt;
    /** 最近一次旧版本回收时间（避免重复全量扫描） */
    private OffsetDateTime purgedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
