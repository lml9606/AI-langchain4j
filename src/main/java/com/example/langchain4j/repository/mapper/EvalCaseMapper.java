package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.EvalCase;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 评测用例表 Mapper。
 */
@Mapper
public interface EvalCaseMapper {

    int insert(EvalCase evalCase);

    List<EvalCase> selectEnabled();

    List<EvalCase> selectAll();

    int updateEnabled(@Param("id") long id, @Param("enabled") boolean enabled);
}
