package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import com.example.langchain4j.graph.resume.model.ResumeDiffBlock;
import com.example.langchain4j.graph.resume.util.LineDiffUtil;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * diff 节点：对原文与改稿做行级 LCS 对齐。
 * <p>
 * diff 是展示辅助，失败不影响改稿本身：异常降级为空列表，不让整条工作流失败。
 */
@Slf4j
@Component
public class ResumeDiffNode implements NodeAction<ResumeOptimizeState> {

    @Override
    public Map<String, Object> apply(ResumeOptimizeState state) {
        Map<String, Object> updates = new HashMap<>();
        try {
            List<ResumeDiffBlock> blocks = LineDiffUtil.diff(state.resumeMd(), state.revisedMd());
            updates.put(ResumeOptimizeState.DIFF,
                    blocks.stream().map(ResumeDiffBlock::toMap).toList());
            log.info("[resume-diff] blocks={}", blocks.size());
        } catch (Exception e) {
            log.warn("[resume-diff] diff failed, degrade to empty: {}", e.getMessage());
            updates.put(ResumeOptimizeState.DIFF, List.of());
        }
        return updates;
    }
}
