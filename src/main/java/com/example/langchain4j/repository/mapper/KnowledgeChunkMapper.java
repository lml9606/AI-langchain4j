package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.KnowledgeChunk;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 知识块表 Mapper（pgvector 向量检索）。
 */
@Mapper
public interface KnowledgeChunkMapper {

    int insert(KnowledgeChunk chunk);

    /**
     * 余弦相似度检索 topK（用户隔离：仅命中公共库或本用户知识块）。
     *
     * @param queryVector 查询向量
     * @param topK        返回条数
     * @param minScore    最小相似度阈值（0~1），过滤低相关结果
     * @param userId      当前用户；null 表示无身份上下文（仅可见公共库）
     */
    List<KnowledgeChunk> searchSimilar(@Param("queryVector") float[] queryVector,
                                       @Param("topK") int topK,
                                       @Param("minScore") double minScore,
                                       @Param("userId") String userId);

    /**
     * 词面相似度检索 topK（pg_trgm 三元组，中文无需分词；混合检索的词面通道，用户隔离同上）。
     *
     * @param query    查询文本
     * @param topK     返回条数
     * @param minScore 最小词面相似度阈值（0~1），过滤零重叠的纯噪声命中
     * @param userId   当前用户；null 表示无身份上下文（仅可见公共库）
     */
    List<KnowledgeChunk> searchLexical(@Param("query") String query,
                                       @Param("topK") int topK,
                                       @Param("minScore") double minScore,
                                       @Param("userId") String userId);

    long count();

    /**
     * 批量插入知识块（单条 INSERT 多 VALUES 语句，天然原子，无需事务包裹）。
     * 文档导入：长文档分块后一次落库，减少 DB 往返。
     */
    int insertBatch(@Param("chunks") List<KnowledgeChunk> chunks);

    /**
     * 按 ID + 用户查询（归属校验：可见范围 = 公共库 + 本人私有库）。
     *
     * @param id     块 ID
     * @param userId 当前用户；null 仅查公共库
     */
    KnowledgeChunk selectById(@Param("id") long id, @Param("userId") String userId);

    /** 更新内容 + 重嵌向量（SQL 层再按 user_id 过滤，双保险防越权；归属由服务层校验后调用） */
    int updateContent(KnowledgeChunk chunk);

    /** 删除知识块（SQL 层按 user_id 过滤：仅本人私有块；公共库块由服务层直接拒绝） */
    int deleteById(@Param("id") long id, @Param("userId") String userId);

    /** 知识库健康度聚合（当前用户可见范围：公共库 + 本人私有库） */
    HealthStats healthStats(@Param("userId") String userId);

    /** 健康度聚合结果：异常块数量驱动治理（补嵌/清理），平均长度观测分块质量 */
    @Data
    class HealthStats {
        /** 可见范围总块数 */
        private long totalChunks;
        /** 私有块数（本人知识） */
        private long privateChunks;
        /** 公共块数（运营维护） */
        private long publicChunks;
        /** 内容为空块数（脏数据，应清理） */
        private long emptyContent;
        /** 无向量块数（嵌入失败残留，应重嵌或清理） */
        private long noEmbedding;
        /** 平均内容长度（观测分块粒度是否合理） */
        private double avgContentLength;
        /** 最近导入时间 */
        private OffsetDateTime lastImportedAt;
    }
}
