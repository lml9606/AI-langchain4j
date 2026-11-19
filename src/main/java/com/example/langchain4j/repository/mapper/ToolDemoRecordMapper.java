package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.ToolDemoRecord;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 工具演示数据 Mapper：所有读写都带 user_id 条件，天然用户隔离。
 */
@Mapper
public interface ToolDemoRecordMapper {

    /** 按用户查询记录；status 为空表示不过滤 */
    List<ToolDemoRecord> selectByUserId(@Param("userId") String userId,
                                        @Param("status") String status,
                                        @Param("limit") int limit);

    /** 按 ID + 用户查询（越权返回 null，不暴露记录是否存在） */
    ToolDemoRecord selectById(@Param("id") long id, @Param("userId") String userId);

    /** 按标题精确匹配（最多取 limit 条，用于识别"重名"歧义） */
    List<ToolDemoRecord> selectByTitle(@Param("userId") String userId,
                                       @Param("title") String title,
                                       @Param("limit") int limit);

    /** 标记完成：只允许把本人的 OPEN 记录改为 DONE（重复调用影响 0 行） */
    int markDone(@Param("id") long id, @Param("userId") String userId);

    /** 演示数据播种（仅 seed 接口使用） */
    int insert(ToolDemoRecord record);

    long countByUserId(@Param("userId") String userId);
}
