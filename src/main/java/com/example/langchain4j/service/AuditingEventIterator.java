package com.example.langchain4j.service;

import com.example.langchain4j.graph.GraphEvent;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.repository.entity.GraphExecution;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.NodeOutput;

import java.util.Iterator;
import java.util.Map;

/**
 * 流式事件迭代器：包装图 NodeOutput 流，逐节点审计并转换为 GraphEvent。
 * <p>
 * NodeOutput 三种形态：
 * <ul>
 *   <li>isSTART()：节点刚被调度（还未执行），推 NODE_START 事件；</li>
 *   <li>isEND()：图整体执行完毕，推 RESULT 事件携带最终答案；</li>
 *   <li>其他：单节点执行完毕，携带该节点更新后的状态，推 NODE_END 事件。
 * </ul>
 * 迭代器耗尽（hasNext=false）时释放会话锁：流式场景下锁跨整个流消费过程，
 * 客户端断连未消费完时锁由 Redisson Watchdog 在持锁线程消亡后兜底释放。
 */
@Slf4j
class AuditingEventIterator implements Iterator<GraphEvent> {

    private final String conversationId;
    private final Iterator<NodeOutput<WorkflowState>> delegate;
    private final AutoCloseable lock;
    private final AuditService auditService;
    private boolean closed = false;

    AuditingEventIterator(String conversationId,
                          Iterator<NodeOutput<WorkflowState>> delegate,
                          AutoCloseable lock,
                          AuditService auditService) {
        this.conversationId = conversationId;
        this.delegate = delegate;
        this.lock = lock;
        this.auditService = auditService;
    }

    @Override
    public boolean hasNext() {
        boolean hasNext = delegate.hasNext();
        if (!hasNext) {
            closeQuietly();
        }
        return hasNext;
    }

    @Override
    public GraphEvent next() {
        NodeOutput<WorkflowState> output = delegate.next();
        WorkflowState state = output.state() != null
                ? output.state()
                : new WorkflowState(Map.of());
        String node = output.node();
        auditService.audit(conversationId, node, GraphExecution.STATUS_SUCCESS,
                state.query(), state.result(), state.error(), null);
        if (output.isSTART()) {
            return GraphEvent.nodeStart(node);
        }
        if (output.isEND()) {
            return GraphEvent.result(node, state.result());
        }
        if (state.error() != null && !state.error().isBlank()) {
            return GraphEvent.error(node, state.error());
        }
        return GraphEvent.nodeEnd(node);
    }

    private void closeQuietly() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            lock.close();
        } catch (Exception e) {
            log.warn("[agent] stream lock release failed: {}", e.getMessage());
        }
    }
}
