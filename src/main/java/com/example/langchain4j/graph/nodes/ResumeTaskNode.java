package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.service.ResumeOptimizeService;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 简历优化触发节点：对话内命中 resume_optimize 意图后的轻量出口。
 * <p>
 * 不调 LLM、不执行简历优化图：查档案 → 落草稿 → 提交后台异步任务，返回一句提示语，
 * 走问答图的 finalize 链路一轮闭环。长任务（最多 180s）在后台跑，不占用会话锁、
 * 不破坏问答图的超时降级语义；完成后由服务层向触发会话回写一条 assistant 通知。
 */
@Slf4j
@Component
public class ResumeTaskNode implements NodeAction<WorkflowState> {

    private final ResumeOptimizeService resumeOptimizeService;

    public ResumeTaskNode(ResumeOptimizeService resumeOptimizeService) {
        this.resumeOptimizeService = resumeOptimizeService;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        Map<String, Object> updates = new HashMap<>();
        try {
            String userId = state.userId();
            if (userId.isBlank()) {
                // 理论上不会发生（服务层入图必注 userId），防御缺省时给出可重试提示
                updates.put(WorkflowState.RESULT, "简历优化服务暂不可用，请稍后在简历优化页面重试。");
                return updates;
            }
            String message = resumeOptimizeService.triggerAsync(
                    userId, state.conversationId(), state.model());
            updates.put(WorkflowState.RESULT, message);
        } catch (Exception e) {
            // 触发失败不阻断对话闭环：给出降级提示，用户可在优化页面重试
            log.warn("[resume-task] trigger failed, degrade to guide message: {}", e.getMessage());
            updates.put(WorkflowState.RESULT, "简历优化服务暂不可用，请稍后在简历优化页面重试。");
        }
        updates.put(WorkflowState.ERROR, "");
        return updates;
    }
}
