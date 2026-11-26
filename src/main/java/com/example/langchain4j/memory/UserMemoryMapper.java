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
     * 唯一键 (user_id, memory_type, memory_key) 冲突时更新内容、来源与有效期；
     * 抽取链路的 important 取历史最大值，手动改重要度走 updateContent。
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

    /**
     * 按唯一键加行锁查询：用于写入前读取旧内容，保证历史记录和主表更新在同一事务内一致。
     * 必须在事务中调用。
     */
    UserMemory selectByKeyForUpdate(@Param("userId") String userId,
                                    @Param("memoryType") String memoryType,
                                    @Param("memoryKey") String memoryKey);

    /** 按主键查询自己的记忆；不存在或不属于当前用户返回 null。 */
    UserMemory selectById(@Param("userId") String userId, @Param("id") Long id);

    /** 按主键加行锁查询，供手动修改时写历史记录使用；必须在事务中调用。 */
    UserMemory selectByIdForUpdate(@Param("userId") String userId, @Param("id") Long id);

    /**
     * 查同用户同类型的近期记忆，供同义合并比较。
     * 只取 ACTIVE/CANDIDATE 且未过期的记录，按更新时间倒序。
     */
    List<UserMemory> selectRecentByType(@Param("userId") String userId,
                                        @Param("memoryType") String memoryType,
                                        @Param("limit") int limit);

    /**
     * 手动修改内容与重要度：允许降低重要度，不参与 upsert 的 GREATEST 规则。
     * 返回 0 表示不存在或不属于当前用户。
     */
    int updateContent(@Param("userId") String userId,
                      @Param("id") Long id,
                      @Param("content") String content,
                      @Param("importance") double importance);

    /** 删除自己的记忆；返回 0 表示不存在或不属于当前用户。 */
    int deleteById(@Param("userId") String userId, @Param("id") Long id);

    /** 记录召回命中：更新最近使用时间与次数，供后续治理低价值记忆。 */
    int markUsed(@Param("userId") String userId, @Param("ids") List<Long> ids);
}
