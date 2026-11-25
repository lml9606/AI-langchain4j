package com.example.langchain4j.sensitive;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 敏感词表 Mapper。
 */
@Mapper
public interface SensitiveWordMapper {

    int insert(SensitiveWord word);

    /** 全部启用词（输出审核缓存加载） */
    List<SensitiveWord> selectEnabled();

    List<SensitiveWord> selectAll();

    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled);

    int deleteById(@Param("id") long id);
}
