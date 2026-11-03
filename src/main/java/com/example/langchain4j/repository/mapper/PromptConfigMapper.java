package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.PromptConfig;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * Prompt 配置表 Mapper。
 */
@Mapper
public interface PromptConfigMapper {

    List<PromptConfig> selectAll();

    PromptConfig selectByKey(@Param("promptKey") String promptKey);

    /** 插入或覆盖（key 冲突时更新文本并递增版本；PG 专属语法，幂等） */
    int upsert(PromptConfig config);

    int deleteByKey(@Param("promptKey") String promptKey);
}
