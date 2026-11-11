package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 兜底节点：统一失败出口。
 * <p>
 * 与问答图的「兜底答案」不同，简历优化失败不能返回半份简历——
 * 这里清空改稿/变更/diff，只保留 error 与 fallback 标记，由服务层转成业务错误。
 */
@Slf4j
@Component
public class ResumeFallbackNode implements NodeAction<ResumeOptimizeState> {

    @Override
    public Map<String, Object> apply(ResumeOptimizeState state) {
        String reason = state.error();
        if (reason == null || reason.isBlank()) {
            reason = "简历优化失败，请稍后重试";
        }
        Map<String, Object> updates = new HashMap<>();
        updates.put(ResumeOptimizeState.ERROR, reason);
        updates.put(ResumeOptimizeState.FALLBACK, true);
        updates.put(ResumeOptimizeState.DONE, true);
        updates.put(ResumeOptimizeState.REVISED_MD, "");
        updates.put(ResumeOptimizeState.CHANGES, List.of());
        updates.put(ResumeOptimizeState.DIFF, List.of());
        // 不回显具体原因内容：校验原因可能包含手机号/邮箱等个人信息，只记录长度
        log.warn("[resume-fallback] workflow failed, reasonLength={}", reason.length());
        return updates;
    }
}
