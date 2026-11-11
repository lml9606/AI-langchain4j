package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 收尾节点：成功路径兜底校验「改稿非空」，统一标记流程完成。
 * 失败路径（fallback）不在这里改写错误，只负责收口。
 */
@Slf4j
@Component
public class ResumeFinalizeNode implements NodeAction<ResumeOptimizeState> {

    @Override
    public Map<String, Object> apply(ResumeOptimizeState state) {
        Map<String, Object> updates = new HashMap<>();
        if (!state.fallback() && (state.revisedMd() == null || state.revisedMd().isBlank())) {
            updates.put(ResumeOptimizeState.ERROR, "简历优化未产出有效改稿");
            updates.put(ResumeOptimizeState.FALLBACK, true);
        }
        updates.put(ResumeOptimizeState.DONE, true);
        log.info("[resume-finalize] fallback={}, revisedChars={}",
                state.fallback(), state.revisedMd() == null ? 0 : state.revisedMd().length());
        return updates;
    }
}
