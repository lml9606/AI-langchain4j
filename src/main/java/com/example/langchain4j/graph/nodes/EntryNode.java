package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 守卫节点：图入口的防御性校验。
 * 1. 空输入直接标记错误（走兜底），不进入 LLM；
 * 2. 长度截断 + 控制字符清理（基础 prompt 注入防护）；
 * 3. 初始化重试计数与多跳计数。
 */
@Slf4j
@Component
public class EntryNode implements NodeAction<WorkflowState> {

    /** 单条输入最大长度：超出部分直接截断，保护下游 token 开销（与 Controller @Size 一致，此处为图内兜底） */
    private static final int MAX_QUERY_LENGTH = 4000;

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：读取用户输入
        String query = state.query();
        // 步骤2：空输入校验 —— 直接标记错误（走兜底），不进入 LLM，并归零跨轮计数（见 perTurnReset）
        if (query == null || query.isBlank()) {
            log.warn("[entry] empty query, route to fallback");
            Map<String, Object> updates = new LinkedHashMap<>();
            updates.put(WorkflowState.ERROR, "输入内容为空");
            updates.putAll(WorkflowState.perTurnReset());
            return updates;
        }
        // 步骤3：防御性清洗 —— 剔除控制字符（防 prompt 注入/防滥用）并去首尾空白
        String cleaned = query
                .replaceAll("[\\p{Cntrl}]", " ")
                .trim();
        // 步骤4：超长截断 —— 超出上限的部分直接丢弃，保护下游 token 开销
        if (cleaned.length() > MAX_QUERY_LENGTH) {
            cleaned = cleaned.substring(0, MAX_QUERY_LENGTH);
        }
        // 步骤5：返回增量更新：清洗后的输入 + 跨轮计数归零 + 清空错误标记
        Map<String, Object> updates = new LinkedHashMap<>();
        updates.put(WorkflowState.QUERY, cleaned);
        updates.put(WorkflowState.ERROR, "");
        updates.putAll(WorkflowState.perTurnReset());
        return updates;
    }
}
