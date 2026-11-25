package com.example.langchain4j.business.quota;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 额度 Mapper：额度读写必须同时校验归属用户。
 */
@Mapper
public interface QuotaMapper {

    int insert(Quota quota);

    Quota selectById(@Param("id") long id, @Param("userId") String userId);

    List<Quota> selectByUserId(@Param("userId") String userId,
                               @Param("enrollmentNo") String enrollmentNo,
                               @Param("activityNo") String activityNo,
                               @Param("month") String month,
                               @Param("limit") int limit);

    int update(Quota quota);

    int delete(@Param("id") long id, @Param("userId") String userId);
}
