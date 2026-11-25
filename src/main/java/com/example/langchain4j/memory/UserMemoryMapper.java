package com.example.langchain4j.memory;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户长期记忆 Mapper。
 *
 * 所有查询强制带 user_id，删除也必须同时匹配 user_id，保证水平越权防御在 SQL 层兜底。
 */
@Mapper
public interface UserMemoryMapper {

    /**
     * 新增或覆盖同一含义的记忆。
     * 唯一键 (user_id, memory_type, memory_key) 冲突时更新内容、重要度、来源与有效期。
     */
    int upsert(UserMemory memory);

    /**
     * 召回 Top N：只取未过期且重要度达标的记忆，按重要度、更新时间倒序。
     */
    List<UserMemory> selectTop(@Param("userId") String userId,
                               @Param("limit") int limit,
                               @Param("minImportance") double minImportance);

    /** 用户查看自己的全部记忆（含重要度较低项），按更新时间倒序。 */
    List<UserMemory> selectByUser(@Param("userId") String userId,
                                  @Param("limit") int limit);

    /** 按唯一键查单条：手动新增/修正后返回带自增 id 的完整记录。 */
    UserMemory selectByKey(@Param("userId") String userId,
                           @Param("memoryType") String memoryType,
                           @Param("memoryKey") String memoryKey);

    /** 删除自己的记忆；返回 0 表示不存在或不属于当前用户。 */
    int deleteById(@Param("userId") String userId, @Param("id") Long id);

    /** 记录召回命中：更新最近使用时间与次数，供后续治理低价值记忆。 */
    int markUsed(@Param("userId") String userId, @Param("ids") List<Long> ids);
}
