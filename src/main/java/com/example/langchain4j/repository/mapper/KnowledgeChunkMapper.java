package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.KnowledgeChunk;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 知识块表 Mapper（pgvector 向量检索）。
 */
@Mapper
public interface KnowledgeChunkMapper {

    int insert(KnowledgeChunk chunk);

    /**
     * 余弦相似度检索 topK。
     *
     * @param queryVector 查询向量
     * @param topK        返回条数
     * @param minScore    最小相似度阈值（0~1），过滤低相关结果
     */
    List<KnowledgeChunk> searchSimilar(@Param("queryVector") float[] queryVector,
                                       @Param("topK") int topK,
                                       @Param("minScore") double minScore);

    long count();
}
