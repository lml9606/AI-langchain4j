package com.example.langchain4j.tool.api.constant;

/**
 * 工具名唯一字符串来源：ToolDefinition、白名单、轨迹和调用方共用。
 *
 * 常量只解决“名字一致”，工具能力、权限和副作用仍以 ToolDefinition 为准。
 */
public final class ToolNames {

    /** 查询当前用户的报名记录。 */
    public static final String QUERY_ENROLLMENT = "query_enrollment";

    /** 查询当前用户的额度记录。 */
    public static final String QUERY_QUOTA = "query_quota";

    /** 查询当前用户的额度交易流水。 */
    public static final String QUERY_TRANSACTION = "query_transaction";

    /** 检索知识库资料。 */
    public static final String SEARCH_KNOWLEDGE = "search_knowledge";

    /** 查询当前用户的演示记录。 */
    public static final String QUERY_DEMO_RECORD = "query_demo_record";

    /** 将当前用户的演示记录标记为完成。 */
    public static final String COMPLETE_DEMO_RECORD = "complete_demo_record";

    /** 契约常量类只提供工具名，禁止实例化。 */
    private ToolNames() {
    }
}
