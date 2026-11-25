package com.example.langchain4j.agent.stream;

/**
 * 流式事件接收端。
 *
 * 返回值表示连接是否仍可写：false 表示客户端已断开或发送失败，图执行应立即停止，
 * 避免继续消耗模型与工具资源。
 */
@FunctionalInterface
public interface AgentStreamSink {

    /**
     * 发送一个事件。
     *
     * @param event 事件类型与载荷
     * @return true=连接可用；false=应停止后续事件发送
     */
    boolean emit(AgentStreamEvent event);
}
