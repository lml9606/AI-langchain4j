package com.example.langchain4j.common.constant;

/**
 * Redis key 前缀的唯一来源：会话锁、确认令牌、限流和 LLM 熔断共用。
 *
 * 集中管理前缀只解决命名冲突和排查一致性问题，不改变各组件自己的过期时间、
 * 并发策略和故障降级行为；具体 key 仍由使用方拼接业务标识。
 */
public final class RedisKeys {

    /** 会话级图执行锁：按 conversationId 隔离。 */
    public static final String SESSION_LOCK_PREFIX = "agent:lock:";

    /** 写工具确认令牌：每个会话只保留一张有效确认卡。 */
    public static final String CONFIRMATION_PREFIX = "agent:confirm:";

    /** 入口/工具限流计数：按限流维度隔离。 */
    public static final String RATE_LIMIT_PREFIX = "agent:rl:";

    /** LLM 熔断失败计数：按模型隔离。 */
    public static final String LLM_CIRCUIT_FAILURES_PREFIX = "agent:llm:cb:failures:";

    /** LLM 熔断打开标记：按模型隔离。 */
    public static final String LLM_CIRCUIT_OPEN_PREFIX = "agent:llm:cb:open:";

    /** 常量类只提供 key 前缀，禁止实例化。 */
    private RedisKeys() {
    }
}
