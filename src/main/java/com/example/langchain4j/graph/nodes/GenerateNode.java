package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.LlmResult;
import com.example.langchain4j.llm.PromptTemplates;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

/**
 * 主生成节点：按意图选择 prompt，结合检索上下文生成回答。
 * 防御策略：LLM 异常（超时/重试耗尽/熔断）→ 标记 error，由路由转兜底节点，
 * 保证图永远有输出，绝不向上裸抛异常。
 */
@Slf4j
@Component
public class GenerateNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;

    public GenerateNode(LlmGateway llmGateway) {
        this.llmGateway = llmGateway;
    }

    @Override
    public Map<String, Object> apply(WorkflowState state) {
        String systemPrompt = selectSystemPrompt(state.intent());
        String userPrompt = buildUserPrompt(state);

        Map<String, Object> updates = new HashMap<>();
        try {
            long start = System.currentTimeMillis();
            LlmResult result = llmGateway.generate(state.model(), systemPrompt, userPrompt);
            log.info("[generate] cost={}ms, outputLength={}",
                    System.currentTimeMillis() - start, result.text().length());
            updates.put(WorkflowState.RESULT, result.text());
            if (result.tokenUsage() != null) {
                updates.put(WorkflowState.TOKEN_USAGE, result.tokenUsage());
            }
            updates.put(WorkflowState.ERROR, "");
        } catch (BizException e) {
            log.error("[generate] llm failed, route to fallback: {}", e.getMessage());
            updates.put(WorkflowState.ERROR, e.getMessage());
        } catch (Exception e) {
            log.error("[generate] unexpected error, route to fallback", e);
            updates.put(WorkflowState.ERROR, "生成失败: " + e.getMessage());
        }
        return updates;
    }

    private String selectSystemPrompt(String intent) {
        if (IntentNode.INTENT_CODE_GENERATE.equals(intent)) {
            return PromptTemplates.CODE_GENERATE_SYSTEM;
        }
        return PromptTemplates.KNOWLEDGE_QA_SYSTEM;
    }

    private String buildUserPrompt(WorkflowState state) {
        StringBuilder sb = new StringBuilder();
        // 校验重试时，附带重试修正指令
        if (state.retryCount() > 0) {
            sb.append(String.format(PromptTemplates.VALIDATE_RETRY_PROMPT, state.query())).append("\n\n");
        }
        sb.append("用户需求：").append(state.query());
        String context = state.context();
        if (context != null && !context.isBlank()) {
            sb.append("\n\n").append(context);
        }
        return sb.toString();
    }
}
