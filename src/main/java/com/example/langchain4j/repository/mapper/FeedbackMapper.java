package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.Feedback;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 反馈表 Mapper。
 */
@Mapper
public interface FeedbackMapper {

    int insert(Feedback feedback);

    List<Feedback> selectByConversationId(@Param("conversationId") String conversationId,
                                          @Param("limit") int limit);

    /** 点踩统计（按天分组，驱动知识治理报表） */
    List<DownStat> selectDownStats(@Param("limit") int limit);

    /** 点踩统计行 */
    @lombok.Data
    class DownStat {
        private String day;
        private long downCount;
    }
}
