package com.example.langchain4j.graph.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.LlmResult;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.repository.entity.Message;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 主生成节点：按意图选择 prompt，结合检索上下文与多轮历史生成回答。
 * 多轮上下文三条路径：
 * <ul>
 *   <li>指代消解：由服务层 QueryRewriter 在入图前完成，节点使用有效查询（改写句）；</li>
 *   <li>原文细节：当前话题段最近 N 轮原文以消息角色注入 messages，模型可直接引用前文内容；</li>
 *   <li>窗口外要点：当前话题段摘要拼入 system prompt，回忆早期历史。</li>
 * </ul>
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
        // 步骤1：按意图选 system prompt（代码生成 / 知识问答两套模板），并拼入当前话题段摘要
        String systemPrompt = selectSystemPrompt(state.intent());
        // 步骤2：窗口外的历史要点（段摘要）拼入 system prompt，帮助模型回忆早期内容
        String summary = state.summary();
        if (summary != null && !summary.isBlank()) {
            systemPrompt = systemPrompt + String.format(PromptTemplates.SUMMARY_BLOCK, summary);
        }
        // 步骤3：拼装 user prompt（重试修正指令 + 有效查询 + 检索上下文）
        String userPrompt = buildUserPrompt(state);
        // 步骤4：组装完整 messages：system + 最近 N 轮历史原文（角色消息）+ 本轮提问
        List<ChatMessage> messages = buildMessages(state, systemPrompt, userPrompt);

        Map<String, Object> updates = new HashMap<>();
        try {
            // 步骤5：调用 LLM 生成，并记录耗时与输出长度
            long start = System.currentTimeMillis();
            LlmResult result = llmGateway.generate(state.model(), messages);
            log.info("[generate] cost={}ms, outputLength={}",
                    System.currentTimeMillis() - start, result.text().length());
            // 步骤6：写入结果与 token 用量（用量可能为空，防御性判空）
            updates.put(WorkflowState.RESULT, result.text());
            if (result.tokenUsage() != null) {
                updates.put(WorkflowState.TOKEN_USAGE, result.tokenUsage());
            }
            // 步骤7：清空错误标记（成功路径），交由路由进入校验节点
            updates.put(WorkflowState.ERROR, "");
        } catch (BizException e) {
            // 步骤8：已知 LLM 异常（超时/重试耗尽/熔断）→ 标记 error，路由转兜底
            log.error("[generate] llm failed, route to fallback: {}", e.getMessage());
            updates.put(WorkflowState.ERROR, e.getMessage());
        } catch (Exception e) {
            // 步骤9：未知异常同样标记 error 转兜底，绝不向上裸抛
            log.error("[generate] unexpected error, route to fallback", e);
            updates.put(WorkflowState.ERROR, "生成失败: " + e.getMessage());
        }
        return updates;
    }

    /** 组装 messages：system + 历史原文（user/assistant 角色）+ 本轮 user 提问 */
    private List<ChatMessage> buildMessages(WorkflowState state, String systemPrompt, String userPrompt) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(systemPrompt));
        // 历史原文注入：模型以真实对话角色看到前文，可直接引用/修改前文内容（改代码、展开某点等）
        for (Map<String, String> item : state.history()) {
            String content = item.get("content");
            if (content == null || content.isBlank()) {
                continue;
            }
            if (Message.ROLE_USER.equals(item.get("role"))) {
                messages.add(UserMessage.from(content));
            } else {
                messages.add(AiMessage.from(content));
            }
        }
        messages.add(UserMessage.from(userPrompt));
        return messages;
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
        // 步骤1：校验重试时（重试计数 > 0），拼入重试修正指令（用有效查询）
        if (state.retryCount() > 0) {
            sb.append(String.format(PromptTemplates.VALIDATE_RETRY_PROMPT, state.effectiveQuery())).append("\n\n");
        }
        // 步骤2：拼入用户需求正文（有效查询：改写后的自包含句，未改写时即原始 query）
        sb.append("用户需求：").append(state.effectiveQuery());
        // 步骤3：有检索上下文时追加，为回答提供知识依据
        String context = state.context();
        if (context != null && !context.isBlank()) {
            sb.append("\n\n").append(context);
        }
        return sb.toString();
    }
}
