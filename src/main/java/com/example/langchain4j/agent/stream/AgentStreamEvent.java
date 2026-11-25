package com.example.langchain4j.agent.stream;

/**
 * SSE 事件：{@code type} 决定前端渲染分支，{@code data} 为可序列化载荷。
 *
 * 事件类型约定：
 * meta  本轮元信息（conversationId/model/answerMode/visibility）
 * node  图节点进度（节点名 + 精简状态）
 * done  最终结果（与同步 chat 的 data 结构一致）
 * error 执行失败（code/message；流开始后无法再返回统一响应体）
 */
public record AgentStreamEvent(String type, Object data) {

    /** 构造事件；type 为空时按 error 处理，避免前端收到无法识别的无名事件。 */
    public AgentStreamEvent {
        type = type == null || type.isBlank() ? "error" : type;
    }

    /** 便捷工厂：调用方不需要关心 record 构造细节。 */
    public static AgentStreamEvent of(String type, Object data) {
        return new AgentStreamEvent(type, data);
    }
}
