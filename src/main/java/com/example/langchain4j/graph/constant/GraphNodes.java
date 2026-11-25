package com.example.langchain4j.graph.constant;

/**
 * 主图节点 ID 的唯一定义。
 *
 * 这些 ID 会写入 LangGraph4j 的 checkpoint 元数据和运行日志，属于图的外部契约；
 * 重命名会影响历史轨迹排查和断点恢复，因此集中维护，禁止在 Builder 中散落字符串。
 */
public final class GraphNodes {

    /** 输入守卫节点：清洗输入、重置会话状态并处理空输入。 */
    public static final String NODE_ENTRY = "entry";

    /** 意图与检索规划节点：一次结构化调用完成意图分类和查询规划。 */
    public static final String NODE_INTENT_PLAN = "intent_plan";

    /** 轻量检索探针节点：判断证据是否足够，决定快链路或完整检索。 */
    public static final String NODE_RETRIEVAL_PROBE = "retrieval_probe";

    /** 完整检索节点：按计划多路召回，或质检失败后的纠错重查。 */
    public static final String NODE_RETRIEVAL_FULL = "retrieval_full";

    /** 回答生成节点：按意图、复杂度和证据组装 prompt 并调用模型。 */
    public static final String NODE_GENERATE = "generate";

    /** 分层质检节点：规则、证据和 LLM 三层校验。 */
    public static final String NODE_VALIDATE = "validate";

    /** 确定性兜底节点：异常或重试耗尽时返回安全降级回答。 */
    public static final String NODE_FALLBACK = "fallback";

    /** 转人工节点：知识库确实无证据时标记根因并交接。 */
    public static final String NODE_HANDOFF = "handoff";

    /** 任务解析节点：判断读写操作并抽取业务参数。 */
    public static final String NODE_TASK_RESOLVE = "task_resolve";

    /** 写操作确认节点：创建确认令牌并透出待确认动作。 */
    public static final String NODE_TASK_CONFIRM = "task_confirm";

    /** 第一段固定只读节点：按目标表调用领域 Service。 */
    public static final String NODE_TASK_READ = "task_read";

    /** 第二段额度只读节点：按报名来源关联额度。 */
    public static final String NODE_TASK_READ_QUOTA = "task_read_quota";

    /** 第二段交易只读节点：按报名或额度来源关联交易流水。 */
    public static final String NODE_TASK_READ_TRANSACTION = "task_read_transaction";

    /** 任务回答节点：把只读或 ReAct 证据整理成最终业务回答。 */
    public static final String NODE_TASK_ANSWER = "task_answer";

    /** ReAct 规划节点：在受控只读工具白名单内选择下一步动作。 */
    public static final String NODE_REACT_PLAN = "react_plan";

    /** ReAct 工具执行节点：执行规划选中的只读工具并记录观察结果。 */
    public static final String NODE_REACT_TOOL = "react_tool";

    /** 统一收尾节点：写回结果、完成标记和待确认动作。 */
    public static final String NODE_FINALIZE = "finalize";

    /** 常量类只提供图契约，禁止实例化。 */
    private GraphNodes() {
    }
}
