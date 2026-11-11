package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.UserResume;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 用户简历档案 Mapper：每用户仅一份，覆盖式 upsert。
 */
@Mapper
public interface UserResumeMapper {

    /** 上传/更新简历档案（user_id 冲突时覆盖 title/content） */
    int upsert(UserResume resume);

    /** 按用户查档案（无档案返回 null） */
    UserResume selectByUserId(@Param("userId") String userId);
}
