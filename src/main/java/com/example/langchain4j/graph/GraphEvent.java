package com.example.langchain4j.graph;

/**
 * 图执行事件（SSE 推送的领域事件，与传输协议解耦）。
 */
public record GraphEvent(String type, String node, String message) {

    /** 节点刚被调度（还未执行） */
    public static final String TYPE_NODE_START = "NODE_START";
    /** 单节点执行完毕 */
    public static final String TYPE_NODE_END = "NODE_END";
    /** 图整体执行完毕，message 携带最终答案 */
    public static final String TYPE_RESULT = "RESULT";
    /** 图内异常（message 携带错误信息；SSE 流消费失败时也复用此类型） */
    public static final String TYPE_ERROR = "ERROR";
    /** 心跳帧类型（预留；实际心跳由 controller 以 SSE comment 帧发送，不走本对象） */
    public static final String TYPE_HEARTBEAT = "HEARTBEAT";

    public static GraphEvent nodeStart(String node) {
        return new GraphEvent(TYPE_NODE_START, node, null);
    }

    public static GraphEvent nodeEnd(String node) {
        return new GraphEvent(TYPE_NODE_END, node, null);
    }

    public static GraphEvent result(String node, String message) {
        return new GraphEvent(TYPE_RESULT, node, message);
    }

    public static GraphEvent error(String node, String message) {
        return new GraphEvent(TYPE_ERROR, node, message);
    }

    public static GraphEvent heartbeat() {
        return new GraphEvent(TYPE_HEARTBEAT, null, null);
    }
}
