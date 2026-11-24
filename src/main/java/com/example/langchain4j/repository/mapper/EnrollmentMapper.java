package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.Enrollment;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 报名表 Mapper：所有用户侧读写都带 user_id 条件，防止水平越权。
 */
@Mapper
public interface EnrollmentMapper {

    int insert(Enrollment enrollment);

    Enrollment selectById(@Param("id") long id, @Param("userId") String userId);

    List<Enrollment> selectByUserId(@Param("userId") String userId,
                                    @Param("limit") int limit);

    int update(Enrollment enrollment);

    int delete(@Param("id") long id, @Param("userId") String userId);
}
