package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.knowledge.VectorSearchService;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 知识检索节点：pgvector 语义检索，为生成节点提供上下文。
 * <p>
 * 两种模式：
 * <ul>
 *   <li>正常模式：用有效查询检索（改写句已做指代消解，向量召回更准）；</li>
 *   <li>重查模式（质检发现回答与资料矛盾后路由回来）：先让 LLM 根据失败原因改写
 *       query（更聚焦矛盾点），再用新 query 检索，避免原 query 拿回同一批矛盾资料。</li>
 * </ul>
 * 防御策略：检索失败（DB/向量服务不可用）返回空上下文，不阻断主流程。
 */
@Slf4j
@Component
public class RetrievalNode implements NodeAction<WorkflowState> {

    private final VectorSearchService vectorSearchService;
    private final LlmGateway llmGateway;

    public RetrievalNode(VectorSearchService vectorSearchService, LlmGateway llmGateway) {
        this.vectorSearchService = vectorSearchService;
        this.llmGateway = llmGateway;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：记录开始时间，用于统计检索耗时
        long start = System.currentTimeMillis();
        // 步骤2：选择检索 query —— 重查模式先改写，正常模式用有效查询
        String searchQuery = resolveSearchQuery(state);
        // 步骤3：向量检索 + 拼装上下文（VectorSearchService 内部已 fail-open：任何异常降级为空列表）
        String context = vectorSearchService.buildContext(
                vectorSearchService.search(searchQuery));
        // 步骤4：记录检索耗时与上下文长度，便于观测检索质量
        log.info("[retrieval] cost={}ms, requery={}, contextLength={}",
                System.currentTimeMillis() - start, !state.validationIssue().isBlank(), context.length());
        // 步骤5：返回增量更新：检索上下文写入状态，供生成节点使用
        return Map.of(WorkflowState.CONTEXT, context);
    }

    /**
     * 选择检索 query：重查模式（质检 grounding 失败）时根据失败原因让 LLM 改写 query，
     * 更聚焦矛盾点；改写失败或非重查场景降级为有效查询（不阻断主流程）。
     *
     * @param state 当前状态（含质检失败类型/原因）
     * @return 实际用于向量检索的 query
     */
    private String resolveSearchQuery(WorkflowState state) {
        if (!ValidateNode.ISSUE_GROUNDING.equals(state.validationIssue())) {
            return state.effectiveQuery();
        }
        String userPrompt = "原查询：\n" + state.effectiveQuery()
                + "\n\n质检失败原因：\n" + state.validationReason();
        try {
            String refined = llmGateway.generate(state.model(),
                    PromptTemplates.REQUERY_SYSTEM, userPrompt).text();
            String cleaned = clean(refined);
            if (!cleaned.isBlank()) {
                log.info("[retrieval] requery refined: {} -> {}", state.effectiveQuery(), cleaned);
                return cleaned;
            }
        } catch (Exception e) {
            log.warn("[retrieval] requery llm failed, fallback to original query: {}", e.getMessage());
        }
        return state.effectiveQuery();
    }

    /** 控制字符清洗 + 首尾空白去除（与入口清洗规则一致） */
    private String clean(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("[\\p{Cntrl}]", " ").trim();
    }
}
