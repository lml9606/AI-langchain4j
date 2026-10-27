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
        // 步骤1：按意图选 system prompt（代码生成 / 知识问答两套模板）
        String systemPrompt = selectSystemPrompt(state.intent());
        // 步骤2：拼装 user prompt（重试修正指令 + 用户需求 + 检索上下文）
        String userPrompt = buildUserPrompt(state);

        Map<String, Object> updates = new HashMap<>();
        try {
            // 步骤3：调用 LLM 生成，并记录耗时与输出长度
            long start = System.currentTimeMillis();
            LlmResult result = llmGateway.generate(state.model(), systemPrompt, userPrompt);
            log.info("[generate] cost={}ms, outputLength={}",
                    System.currentTimeMillis() - start, result.text().length());
            // 步骤4：写入结果与 token 用量（用量可能为空，防御性判空）
            updates.put(WorkflowState.RESULT, result.text());
            if (result.tokenUsage() != null) {
                updates.put(WorkflowState.TOKEN_USAGE, result.tokenUsage());
            }
            // 步骤5：清空错误标记（成功路径），交由路由进入校验节点
            updates.put(WorkflowState.ERROR, "");
        } catch (BizException e) {
            // 步骤6：已知 LLM 异常（超时/重试耗尽/熔断）→ 标记 error，路由转兜底
            log.error("[generate] llm failed, route to fallback: {}", e.getMessage());
            updates.put(WorkflowState.ERROR, e.getMessage());
        } catch (Exception e) {
            // 步骤7：未知异常同样标记 error 转兜底，绝不向上裸抛
            log.error("[generate] unexpected error, route to fallback", e);
            updates.put(WorkflowState.ERROR, "生成失败: " + e.getMessage());
        }
        return updates;
    }

    private String selectSystemPrompt(String intent) {
        // 步骤1：代码生成意图 → 代码专用模板
        if (IntentNode.INTENT_CODE_GENERATE.equals(intent)) {
            return PromptTemplates.CODE_GENERATE_SYSTEM;
        }
        // 步骤2：其余（含默认意图）→ 知识问答模板
        return PromptTemplates.KNOWLEDGE_QA_SYSTEM;
    }

    private String buildUserPrompt(WorkflowState state) {
        StringBuilder sb = new StringBuilder();
        // 步骤1：校验重试时（重试计数 > 0），拼入重试修正指令
        if (state.retryCount() > 0) {
            sb.append(String.format(PromptTemplates.VALIDATE_RETRY_PROMPT, state.query())).append("\n\n");
        }
        // 步骤2：拼入用户需求正文
        sb.append("用户需求：").append(state.query());
        // 步骤3：有检索上下文时追加，为回答提供知识依据
        String context = state.context();
        if (context != null && !context.isBlank()) {
            sb.append("\n\n").append(context);
        }
        return sb.toString();
    }
}
