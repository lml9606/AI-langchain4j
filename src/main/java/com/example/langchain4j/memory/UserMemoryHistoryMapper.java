package com.example.langchain4j.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 长期记忆变更历史 Mapper。
 *
 * 主表保持一 key 一行，旧内容写历史表，既能“以新为准”，又保留变更审计。
 * 查询不经过本表，只在写入和人工排查时使用。
 */
@Mapper
public interface UserMemoryHistoryMapper {

    /**
     * 写入一次内容变更。
     *
     * @param memoryId     主表记忆 ID
     * @param userId       所属用户（同时用于水平越权排查）
     * @param memoryType   记忆类型
     * @param memoryKey    稳定 key
     * @param oldContent   变更前内容
     * @param newContent   变更后内容
     * @param changeSource EXTRACT / MANUAL
     * @return 影响行数
     */
    int insert(@Param("memoryId") Long memoryId,
               @Param("userId") String userId,
               @Param("memoryType") String memoryType,
               @Param("memoryKey") String memoryKey,
               @Param("oldContent") String oldContent,
               @Param("newContent") String newContent,
               @Param("changeSource") String changeSource);
}
