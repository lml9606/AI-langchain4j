package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.Quota;
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
                               @Param("limit") int limit);

    int update(Quota quota);

    int delete(@Param("id") long id, @Param("userId") String userId);
}
