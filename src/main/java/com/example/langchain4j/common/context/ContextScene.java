package com.example.langchain4j.common.context;

/**
 * LLM 调用场景。
 *
 * 每个场景有自己的可用输入预算和块权重：查询改写只需要最近两轮对话，
 * 段摘要要吃几十条消息，生成链路要拼满五块。预算按场景配，而不是全局一个数。
 */
public enum ContextScene {

    /** 查询改写：最近两轮历史 + 当前 query，无检索。 */
    REWRITE,
    /** 意图与子查询规划：system + query。 */
    INTENT_PLAN,
    /** 知识问答生成：五块全量。 */
    GENERATE,
    /** 答案质检：query + 检索资料 + 候选回答。 */
    VALIDATE,
    /** 质检失败后的纠错重查：query + 失败原因。 */
    REQUERY,
    /** 任务解析：system + query。 */
    TASK_RESOLVE,
    /** ReAct 规划：system + query + 工具清单 + 观察历史。 */
    REACT_PLAN,
    /** 任务回答：system + 证据 + 轨迹。 */
    TASK_ANSWER,
    /** 段摘要：system + 旧摘要 + 待压缩消息。 */
    SUMMARY,
    /** 长期记忆抽取：system + query + answer。 */
    MEMORY_EXTRACT
}
