package com.example.langchain4j.business.transaction;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 额度交易流水 Mapper：只允许操作用户自己的交易记录。
 */
@Mapper
public interface QuotaTransactionMapper {

    int insert(QuotaTransaction transaction);

    QuotaTransaction selectById(@Param("id") long id, @Param("userId") String userId);

    List<QuotaTransaction> selectByUserId(@Param("userId") String userId,
                                          @Param("quotaId") Long quotaId,
                                          @Param("enrollmentId") Long enrollmentId,
                                          @Param("limit") int limit);

    int update(QuotaTransaction transaction);

    int delete(@Param("id") long id, @Param("userId") String userId);
}
