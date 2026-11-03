package com.example.langchain4j.repository.mapper;

import com.example.langchain4j.repository.entity.EvalResult;
import lombok.Data;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 评测结果表 Mapper。
 */
@Mapper
public interface EvalResultMapper {

    int insert(EvalResult result);

    /** 按批次取全部结果（正序，供报告组装与指标聚合） */
    List<EvalResult> selectByRunId(@Param("runId") String runId);

    /** 取最近一批评测的 run_id（无数据返回 null） */
    String selectLatestRunId();

    /** 报告汇总：按批次聚合平均召回/忠实度与成功失败数（倒序取最近 limit 批） */
    List<RunStat> selectRunStats(@Param("limit") int limit);

    /** 批次级汇总行（MyBatis 聚合查询映射：AVG 忽略 NULL，符合“无关键词/判分失败不拖低指标”语义） */
    @Data
    class RunStat {
        private String runId;
        private BigDecimal avgRecall;
        private BigDecimal avgFaithfulness;
        private long total;
        private long failed;
        private OffsetDateTime createdAt;
    }
}
