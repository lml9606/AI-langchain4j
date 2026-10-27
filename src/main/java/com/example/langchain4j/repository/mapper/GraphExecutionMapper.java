package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.GraphExecution;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 图执行审计表 Mapper。
 */
@Mapper
public interface GraphExecutionMapper {

    int insert(GraphExecution execution);

    List<GraphExecution> selectByConversationId(@Param("conversationId") String conversationId,
                                                @Param("limit") int limit);
}
