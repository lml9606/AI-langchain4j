package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.knowledge.VectorSearchService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识检索节点：pgvector 语义检索，为生成节点提供上下文。
 * 防御策略：检索失败（DB/向量服务不可用）返回空上下文，不阻断主流程。
 */
@Slf4j
@Component
public class RetrievalNode implements NodeAction<WorkflowState> {

    private final VectorSearchService vectorSearchService;

    public RetrievalNode(VectorSearchService vectorSearchService) {
        this.vectorSearchService = vectorSearchService;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：记录开始时间，用于统计检索耗时
        long start = System.currentTimeMillis();
        // 步骤2：向量检索 + 拼装上下文（VectorSearchService 内部已 fail-open：任何异常降级为空列表）
        String context = vectorSearchService.buildContext(
                vectorSearchService.search(state.query()));
        // 步骤3：记录检索耗时与上下文长度，便于观测检索质量
        log.info("[retrieval] cost={}ms, contextLength={}",
                System.currentTimeMillis() - start, context.length());
        // 步骤4：返回增量更新：检索上下文写入状态，供生成节点使用
        return Map.of(WorkflowState.CONTEXT, context);
    }
}
