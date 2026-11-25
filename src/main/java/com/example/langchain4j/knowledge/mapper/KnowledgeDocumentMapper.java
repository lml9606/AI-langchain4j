package com.example.langchain4j.knowledge.mapper;

import com.example.langchain4j.knowledge.entity.KnowledgeDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 文档主表 Mapper：版本指针、导入互斥与旧版本回收。
 */
@Mapper
public interface KnowledgeDocumentMapper {

    /** 按逻辑文档 ID + 用户查询；不存在返回 null */
    KnowledgeDocument selectByDocumentId(@Param("documentId") String documentId,
                                         @Param("userId") String userId);

    /** 幂等插入文档主表行（并发导入时靠唯一约束收敛为一个赢家） */
    int insertIfAbsent(KnowledgeDocument document);

    /**
     * 抢占导入权：把 READY/FAILED 置为 IMPORTING。
     * 超过 staleMinutes 仍为 IMPORTING 视为进程异常退出，允许新任务接管，避免永久卡死。
     *
     * @return 影响行数；0 表示已有导入在进行
     */
    int startImport(@Param("documentId") String documentId,
                    @Param("userId") String userId,
                    @Param("staleMinutes") int staleMinutes);

    /**
     * 发布新版本：只更新 active_version 与状态指针，是整条链路唯一需要事务保护的一行更新。
     *
     * @return 影响行数；0 表示导入权已被接管，本次写入应作废
     */
    int activate(@Param("documentId") String documentId,
                 @Param("userId") String userId,
                 @Param("version") int version,
                 @Param("contentHash") String contentHash,
                 @Param("source") String source);

    /** 导入失败：释放导入权并记录 FAILED（active_version 保持不变，旧版本继续对外可读） */
    int markImportFailed(@Param("documentId") String documentId,
                         @Param("userId") String userId);

    /** 标记旧版本已回收（避免 GC 每轮重复扫描同一文档） */
    int markPurged(@Param("documentId") String documentId,
                   @Param("userId") String userId);

    /** 待回收文档：发布已过保留期，且上次回收发生在本次发布之前 */
    List<KnowledgeDocument> selectPurgeCandidates(@Param("retentionHours") int retentionHours,
                                                  @Param("limit") int limit);
}
