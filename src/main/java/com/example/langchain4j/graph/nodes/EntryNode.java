package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 守卫节点：图入口的防御性校验。
 * 1. 空输入直接标记错误（走兜底），不进入 LLM；
 * 2. 长度截断 + 控制字符清理（基础 prompt 注入防护）；
 * 3. 初始化重试计数。
 */
@Slf4j
@Component
public class EntryNode implements NodeAction<WorkflowState> {

    private static final int MAX_QUERY_LENGTH = 4000;

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        String query = state.query();
        if (query == null || query.isBlank()) {
            log.warn("[entry] empty query, route to fallback");
            return Map.of(
                    WorkflowState.ERROR, "输入内容为空",
                    WorkflowState.RETRY_COUNT, 0);
        }
        // 防御性清洗：截断超长输入，剔除控制字符（防注入/防滥用）
        String cleaned = query
                .replaceAll("[\\p{Cntrl}]", " ")
                .trim();
        if (cleaned.length() > MAX_QUERY_LENGTH) {
            cleaned = cleaned.substring(0, MAX_QUERY_LENGTH);
        }
        return Map.of(
                WorkflowState.QUERY, cleaned,
                WorkflowState.RETRY_COUNT, 0,
                WorkflowState.ERROR, "");
    }
}
