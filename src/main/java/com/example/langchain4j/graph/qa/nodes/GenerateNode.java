package com.example.langchain4j.graph.qa.nodes;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.context.Allocation;
import com.example.langchain4j.common.context.BlockType;
import com.example.langchain4j.common.context.ContextAssembler;
import com.example.langchain4j.common.context.ContextBlock;
import com.example.langchain4j.common.context.ContextScene;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.conversation.Message;
import com.example.langchain4j.graph.qa.model.AnswerBudget;
import com.example.langchain4j.graph.qa.model.QaIntent;
import com.example.langchain4j.graph.qa.support.AnswerBudgetResolver;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.guard.OutputGuardService;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.model.LlmRequest;
import com.example.langchain4j.llm.model.LlmResult;
import com.example.langchain4j.prompt.PromptTemplates;
import com.example.langchain4j.prompt.PromptTemplateService;
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
 *
 * 多轮上下文分三部分：指代消解由服务层 QueryRewriter 在入图前完成，节点使用改写后的
 * 有效查询；当前话题段最近 N 轮原文以消息角色注入 messages，模型可以直接引用前文；
 * 窗口外要点由当前话题段摘要拼入 system prompt。
 *
 * 防御策略：LLM 异常（超时/重试耗尽/熔断）→ 标记 error，由路由转兜底节点，
 * 保证图永远有输出，绝不向上裸抛异常。
 */
@Slf4j
@Component
public class GenerateNode implements NodeAction<WorkflowState> {

    private final LlmGateway llmGateway;
    private final OutputGuardService outputGuardService;
    private final PromptTemplateService promptTemplateService;
    private final AnswerBudgetResolver answerBudgetResolver;
    private final ContextAssembler contextAssembler;
    private final AgentProperties properties;

    /** 注入 LLM、输出安全审核、提示词服务与上下文装配器。 */
    public GenerateNode(LlmGateway llmGateway, OutputGuardService outputGuardService,
                        PromptTemplateService promptTemplateService,
                        AnswerBudgetResolver answerBudgetResolver,
                        ContextAssembler contextAssembler, AgentProperties properties) {
        this.llmGateway = llmGateway;
        this.outputGuardService = outputGuardService;
        this.promptTemplateService = promptTemplateService;
        this.answerBudgetResolver = answerBudgetResolver;
        this.contextAssembler = contextAssembler;
        this.properties = properties;
    }

    /** 按意图组装 Prompt、调用模型生成回答，并执行输出安全审核。 */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：按意图选基础 system prompt（代码生成 / 知识问答两套模板）
        List<String> systemParts = new ArrayList<>();
        systemParts.add(selectSystemPrompt(state.intent()));
        // 步骤2：窗口外的历史要点（段摘要）作为独立段落，便于预算分配时单独计量
        String summary = state.summary();
        if (summary != null && !summary.isBlank()) {
            // 提示词走配置化解析：DB 有覆盖用 DB 版（含字面 % 等非法格式时兜底默认模板），否则兜底代码默认值
            systemParts.add(promptTemplateService.resolveFormatted(
                    "SUMMARY_BLOCK", PromptTemplates.SUMMARY_BLOCK, summary));
        }
        // 步骤3：用户长期记忆单独成段；与检索资料冲突时提示词要求以当前信息为准
        String memory = state.memory();
        if (memory != null && !memory.isBlank()) {
            systemParts.add(promptTemplateService.resolveFormatted(
                    "MEMORY_BLOCK", PromptTemplates.MEMORY_BLOCK, memory));
        }
        // 步骤4：按意图 + 复杂度 + 回答模式选择输出预算；复杂问题多答、简单问题短答。
        AnswerBudget budget = answerBudgetResolver.resolve(state);
        String lengthNote = lengthNote(budget);
        if (!lengthNote.isBlank()) {
            systemParts.add(lengthNote);
        }
        // 步骤5：问题块 = 重试修正指令 + 用户需求 + 资料不足声明，三者在语义上同属本轮任务
        List<String> queryParts = buildQueryParts(state);
        // 步骤6：历史必须在预算分配前保持原始顺序，装配器按“保留尾部”丢弃最旧消息
        List<Map<String, String>> historyEntries = usableHistory(state);
        // 步骤7：一次统一分配，取代各块各自截断
        Allocation allocation = contextAssembler.allocate(ContextScene.GENERATE, List.of(
                ContextBlock.of(BlockType.SYSTEM, systemParts),
                ContextBlock.single(BlockType.RETRIEVAL, state.context()),
                ContextBlock.of(BlockType.QUERY, queryParts),
                ContextBlock.of(BlockType.HISTORY, historyEntries.stream()
                        .map(item -> item.get("content")).toList())));
        // 步骤8：按分配结果组装消息，历史条数对齐原始列表的尾部
        List<ChatMessage> messages = renderMessages(allocation, historyEntries);

        Map<String, Object> updates = new HashMap<>();
        try {
            // 步骤5：调用 LLM 生成，并记录耗时与输出长度
            long start = System.currentTimeMillis();
            LlmResult result = generate(state, messages, budget);
            log.info("[generate] cost={}ms, model={}, intent={}, complexity={}, answerMode={}, budgetChars={}, budgetTokens={}, retries={}, history={}, contextLength={}, outputLength={}, inputTokens={}, outputTokens={}, tokenUsage={}, truncated={}",
                    System.currentTimeMillis() - start, state.model(), state.intent(), state.queryPlan().complexity(),
                    state.answerMode(), budget.maxChars(), budget.maxOutputTokens(), state.retryCount(),
                    state.history().size(), state.context().length(), result.text().length(),
                    result.inputTokens(), result.outputTokens(), result.tokenUsage(), result.truncated());
            // 步骤5.5：输出侧审核：命中敏感词替换为拒答文案（不透出命中词，防用户绕过测试）
            String text = result.text();
            if (outputGuardService.blocked(text)) {
                log.warn("[generate] output blocked by sensitive word guard");
                text = OutputGuardService.BLOCKED_ANSWER;
            }
            // 步骤6：写入结果与 token 用量（用量可能为空，防御性判空）
            updates.put(WorkflowState.RESULT, text);
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

    /**
     * 生成调用：所有意图统一使用预算中的输出 token 上限；
     * 代码生成的字数提示为 0（不限制字数），只保留 token 硬上限。
     */
    private LlmResult generate(WorkflowState state, List<ChatMessage> messages, AnswerBudget budget) {
        return llmGateway.generate(LlmRequest.of(state.model(), messages)
                .scene("generate")
                .maxOutputTokens(budget.maxOutputTokens()));
    }

    /** 追加动态长度约束；代码生成 budgetChars=0，不注入字数限制。 */
    private String lengthNote(AnswerBudget budget) {
        if (!budget.hasCharLimit()) {
            return "";
        }
        return promptTemplateService.resolveFormatted(
                "ANSWER_LENGTH_NOTE", PromptTemplates.ANSWER_LENGTH_NOTE,
                budget.styleNote(), budget.maxChars());
    }

    /**
     * 构造本轮问题块：重试修正指令 + 用户需求 + 资料不足声明。
     * 三者都属于“本轮要回答什么”，作为同一个强制块参与预算，不做裁剪。
     */
    private List<String> buildQueryParts(WorkflowState state) {
        List<String> parts = new ArrayList<>();
        // 步骤1：校验重试时带上失败原因，让模型知道上一版为什么没通过
        if (state.retryCount() > 0) {
            String reason = state.validation().reason();
            String reasonPart = (reason == null || reason.isBlank()) ? "" : "（原因：" + reason + "）";
            parts.add(promptTemplateService.resolveFormatted(
                    "VALIDATE_RETRY_PROMPT", PromptTemplates.VALIDATE_RETRY_PROMPT,
                    reasonPart, state.effectiveQuery()));
        }
        // 步骤2：用户需求正文（改写后的自包含句，未改写时即原始 query）
        parts.add("用户需求：" + state.effectiveQuery());
        // 步骤3：检索证据不充分时声明边界，防模型在资料缺失时脑补
        if (state.contextIncomplete()) {
            parts.add(promptTemplateService.resolve(
                    "CONTEXT_INCOMPLETE_NOTE", PromptTemplates.CONTEXT_INCOMPLETE_NOTE));
        }
        return parts;
    }

    /** 过滤空内容的历史消息，保证装配前后的条数能按索引对齐。 */
    private List<Map<String, String>> usableHistory(WorkflowState state) {
        return state.history().stream()
                .filter(item -> item != null && item.get("content") != null
                        && !item.get("content").isBlank())
                .toList();
    }

    /**
     * 按分配结果组装消息列表。
     *
     * 历史按“保留尾部”规则裁剪，所以装配结果的前 N 条对应原始列表的最后 N 条，
     * 角色信息按偏移量对齐，不会出现 user/assistant 错位。
     */
    private List<ChatMessage> renderMessages(Allocation allocation,
                                             List<Map<String, String>> historyEntries) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(SystemMessage.from(allocation.content(BlockType.SYSTEM)));
        List<String> kept = allocation.segments(BlockType.HISTORY);
        int offset = Math.max(0, historyEntries.size() - kept.size());
        for (int i = 0; i < kept.size(); i++) {
            Map<String, String> source = historyEntries.get(offset + i);
            String content = kept.get(i);
            if (Message.ROLE_USER.equals(source.get("role"))) {
                messages.add(UserMessage.from(content));
            } else {
                messages.add(AiMessage.from(content));
            }
        }
        messages.add(UserMessage.from(renderUserMessage(allocation)));
        return messages;
    }

    /**
     * 渲染本轮 user 消息。
     *
     * questionLast=true 时资料在前、问题在后，避免长文档把问题挤到上下文中部；
     * false 时保留旧顺序（问题在前），用于布局出问题时快速回退。
     */
    private String renderUserMessage(Allocation allocation) {
        String query = allocation.content(BlockType.QUERY);
        String context = allocation.content(BlockType.RETRIEVAL);
        if (context.isBlank()) {
            return query;
        }
        if (!properties.getContext().isQuestionLast()) {
            return query + "\n\n" + context;
        }
        return promptTemplateService.resolve(
                "CONTEXT_BLOCK_HEADER", PromptTemplates.CONTEXT_BLOCK_HEADER)
                + "\n" + context
                + promptTemplateService.resolve(
                "QUESTION_BLOCK_HEADER", PromptTemplates.QUESTION_BLOCK_HEADER)
                + "\n" + query;
    }

    /** 按意图选择系统提示词模板。 */
    private String selectSystemPrompt(String intent) {
        // 步骤1：代码生成意图 → 代码专用模板（提示词走配置化解析，DB 覆盖优先）
        if (QaIntent.CODE_GENERATE.equals(intent)) {
            return promptTemplateService.resolve("CODE_GENERATE_SYSTEM", PromptTemplates.CODE_GENERATE_SYSTEM);
        }
        // 步骤2：闲聊意图 → 闲聊模板（无检索上下文，引导用户说出需求）
        if (QaIntent.CHITCHAT.equals(intent)) {
            return promptTemplateService.resolve("CHITCHAT_SYSTEM", PromptTemplates.CHITCHAT_SYSTEM);
        }
        // 步骤3：其余（含默认意图）→ 知识问答模板
        return promptTemplateService.resolve("KNOWLEDGE_QA_SYSTEM", PromptTemplates.KNOWLEDGE_QA_SYSTEM);
    }

}
