package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 质量校验节点：对生成结果做基础质量门禁。
 * 规则：非空、最小长度、未包含模型自身错误提示。
 * 校验失败返回失败标记，由路由决定重试或兜底（有限重试环）。
 */
@Slf4j
@Component
public class ValidateNode implements NodeAction<WorkflowState> {

    private static final int MIN_RESULT_LENGTH = 5;

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        String result = state.result();
        boolean valid = isValid(result);
        if (valid) {
            return Map.of(WorkflowState.VALIDATED, true);
        }
        // 失败：重试计数 +1 并写失败标记，交由路由判断重试或兜底
        int retries = state.retryCount() + 1;
        log.warn("[validate] failed, retryCount={}, resultLength={}",
                retries, result == null ? 0 : result.length());
        return Map.of(
                WorkflowState.RETRY_COUNT, retries,
                WorkflowState.VALIDATED, false);
    }

    private boolean isValid(String result) {
        if (result == null || result.isBlank()) {
            return false;
        }
        String trimmed = result.trim();
        if (trimmed.length() < MIN_RESULT_LENGTH) {
            return false;
        }
        // 模型自曝异常的输出视为无效
        return !trimmed.contains("抱歉，我暂时无法")
                && !trimmed.contains("I apologize");
    }
}
