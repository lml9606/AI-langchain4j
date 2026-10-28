package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 转人工节点：知识库检索无结果时的出口。
 * 返回固定转人工提示，不进入 LLM 生成（避免模型在无资料时编造答案）。
 */
@Slf4j
@Component
public class HandoffNode implements NodeAction<WorkflowState> {

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：记录触发转人工的意图与查询，便于排查检索质量
        log.warn("[handoff] no knowledge match, intent={}, query={}",
                state.intent(), state.effectiveQuery());
        // 步骤2：返回增量更新：固定转人工提示
        return Map.of(WorkflowState.RESULT, PromptTemplates.HANDOFF_ANSWER);
    }
}
