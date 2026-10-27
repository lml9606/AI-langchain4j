package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 兜底节点：所有异常路径的最终出口，输出固定降级答案，保证图永远有输出。
 */
@Slf4j
@Component
public class FallbackNode implements NodeAction<WorkflowState> {

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：记录进入兜底的原因（错误信息 + 当前意图），便于问题排查
        log.warn("[fallback] entering fallback, error={}, intent={}",
                state.error(), state.intent());
        // 步骤2：返回增量更新：固定降级答案 + 兜底标记 + 保留原始错误信息
        return Map.of(
                WorkflowState.RESULT, PromptTemplates.FALLBACK_ANSWER,
                WorkflowState.FALLBACK, true,
                WorkflowState.ERROR, state.error());
    }
}
