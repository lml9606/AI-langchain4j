package com.example.langchain4j.common.constant;

/**
 * 只读查询返回行数的统一边界。
 *
 * 固定只读节点和 ReAct 只读工具共用同一组默认值与硬上限，避免同一业务表
 * 因入口不同返回不同规模的数据；数据库访问仍由各领域 Service 负责。
 */
public final class QueryLimits {

    /** 未显式传 limit 时返回的记录数，兼顾回答完整性和 prompt 体积。 */
    public static final int DEFAULT_ROW_LIMIT = 5;

    /** 单次查询允许返回的最大记录数，防止模型或调用方放大查询结果。 */
    public static final int MAX_ROW_LIMIT = 20;

    /** 常量类只提供查询边界，禁止实例化。 */
    private QueryLimits() {
    }
}
