package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.ResumeRevision;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 简历优化记录 Mapper。
 */
@Mapper
public interface ResumeRevisionMapper {

    int insert(ResumeRevision revision);

    ResumeRevision selectByRevisionId(@Param("revisionId") String revisionId);

    List<ResumeRevision> selectByUser(@Param("userId") String userId,
                                      @Param("limit") int limit);

    /** 生成成功：写回改稿/变更/diff，状态置 DONE，清空 error */
    int markDone(@Param("revisionId") String revisionId,
                 @Param("revisedMd") String revisedMd,
                 @Param("changesJson") String changesJson,
                 @Param("diffJson") String diffJson,
                 @Param("model") String model,
                 @Param("tokenUsage") Integer tokenUsage);

    /** 生成失败：状态置 ERROR，记录失败原因 */
    int markError(@Param("revisionId") String revisionId,
                  @Param("error") String error);

    /** 用户确认保存：状态置 SAVED */
    int markSaved(@Param("revisionId") String revisionId);
}
