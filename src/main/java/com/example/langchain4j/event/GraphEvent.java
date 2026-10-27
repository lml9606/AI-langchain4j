package com.example.langchain4j.event;

/**
 * 图执行事件（SSE 推送的领域事件，与传输协议解耦）。
 */
public record GraphEvent(String type, String node, String message) {

    public static final String TYPE_NODE_START = "NODE_START";
    public static final String TYPE_NODE_END = "NODE_END";
    public static final String TYPE_RESULT = "RESULT";
    public static final String TYPE_ERROR = "ERROR";
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
