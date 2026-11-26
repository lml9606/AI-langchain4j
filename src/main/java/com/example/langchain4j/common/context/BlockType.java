package com.example.langchain4j.common.context;

/**
 * 上下文块的类型。
 *
 * 三类语义决定分配算法怎么对待它：
 * 1. mandatory 块（system/query/answer）是本次调用成立的前提，先扣预算且不参与竞争，
 *    装不下就整体失败，不做静默截断；
 * 2. elastic 块（history）是弹性缓冲，保底之后拿全部剩余预算，用来填满窗口；
 * 3. 其余可分配块（retrieval/evidence/tools/summary/memory）按保底 + 权重参与分配。
 */
public enum BlockType {

    /** 系统提示词：稳定指令，模型行为的基础，不允许截断。 */
    SYSTEM(true, false),
    /** 用户本轮问题：本次调用的核心输入，不允许截断。 */
    QUERY(true, false),
    /** 必须完整参与判断的既有文本（如质检里的候选回答），不允许截断。 */
    ANSWER(true, false),

    /** 检索资料：回答依据，优先级最高，只按片段从尾部丢弃。 */
    RETRIEVAL(false, false),
    /** 工具返回证据：任务链路的事实来源，语义等价于检索资料。 */
    EVIDENCE(false, false),
    /** 工具清单：ReAct 规划可见的动作集合，过多会干扰选择，价值随规模下降。 */
    TOOLS(false, false),
    /** 多轮历史：弹性块，按时间正序排列，装不下时丢弃最旧的前缀。 */
    HISTORY(false, true, true),
    /** 话题段摘要：压缩后的远期信息，价值低于最近历史和检索资料。 */
    SUMMARY(false, false),
    /** 用户长期记忆：个性化信息，最容易在预算紧张时被牺牲。 */
    MEMORY(false, false);

    private final boolean mandatory;
    private final boolean elastic;
    private final boolean keepTail;

    BlockType(boolean mandatory, boolean elastic) {
        this(mandatory, elastic, false);
    }

    BlockType(boolean mandatory, boolean elastic, boolean keepTail) {
        this.mandatory = mandatory;
        this.elastic = elastic;
        this.keepTail = keepTail;
    }

    /** 是否为强制块：强制块不参与预算竞争，超限直接失败。 */
    public boolean mandatory() {
        return mandatory;
    }

    /** 是否为弹性块：弹性块拿分配后的全部剩余预算。 */
    public boolean elastic() {
        return elastic;
    }

    /**
     * 是否从尾部保留片段。
     *
     * 历史按时间正序传入，预算不足时应丢弃最旧的消息，所以从尾部保留；
     * 其余块（检索按得分降序、证据按重要度降序）从头部保留即可。
     */
    public boolean keepTail() {
        return keepTail;
    }
}
