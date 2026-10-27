package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 收尾节点：结果兜底组装（防空输出）并标记流程完成。
 * 持久化落库由 service 层完成，节点保持纯净。
 */
@Slf4j
@Component
public class FinalizeNode implements NodeAction<WorkflowState> {

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：读取生成结果
        String result = state.result();
        // 步骤2：最后一道防线 —— 任何路径到达这里都保证 result 非空
        if (result == null || result.isBlank()) {
            log.warn("[finalize] empty result, apply last-resort answer");
            result = "抱歉，未能生成有效回答，请稍后重试。";
        }
        // 步骤3：记录收尾结果（是否兜底 + 结果长度），方便追踪出口状态
        log.info("[finalize] done, fallback={}, resultLength={}",
                state.fallback(), result.length());
        // 步骤4：返回增量更新：最终结果 + 流程完成标记
        return Map.of(
                WorkflowState.RESULT, result,
                WorkflowState.DONE, true);
    }
}
