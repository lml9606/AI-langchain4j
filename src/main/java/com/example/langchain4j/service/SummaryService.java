package com.example.langchain4j.service;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.llm.LlmGateway;
import com.example.langchain4j.llm.PromptTemplates;
import com.example.langchain4j.repository.entity.ConversationSummary;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.mapper.MessageMapper;
import com.example.langchain4j.repository.mapper.SummaryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 段摘要服务（短期记忆第二层）：把滑出窗口的历史异步压缩成段摘要。
 * <p>
 * 幂等设计：covered_message_id 是增量锚点。摘要任务失败或线程池满被丢弃时
 * 不推进锚点，下一轮聊天重新触发即可续跑，不丢消息、不重复覆盖。
 * <p>
 * 隔离设计：摘要走独立的 summaryExecutor（单线程 + 有界队列），
 * LLM 调用慢也不占用图执行线程池；队列满静默丢弃，降级为“下轮再摘要”。
 */
@Slf4j
@Service
public class SummaryService {

    /** 摘要输入单条消息截断长度：压缩只看要点，全文输入浪费 token */
    private static final int MAX_INPUT_ITEM_LENGTH = 2000;
    /** 单次任务最多处理的批次数：防异常情况下任务无限循环 */
    private static final int MAX_TASK_LOOPS = 3;

    private final SummaryMapper summaryMapper;
    private final MessageMapper messageMapper;
    private final LlmGateway llmGateway;
    private final AgentProperties properties;
    private final ThreadPoolTaskExecutor summaryExecutor;

    public SummaryService(SummaryMapper summaryMapper,
                          MessageMapper messageMapper,
                          LlmGateway llmGateway,
                          AgentProperties properties,
                          ThreadPoolTaskExecutor summaryExecutor) {
        this.summaryMapper = summaryMapper;
        this.messageMapper = messageMapper;
        this.llmGateway = llmGateway;
        this.properties = properties;
        this.summaryExecutor = summaryExecutor;
    }

    /**
     * 段内未被摘要覆盖的消息达到阈值时，提交异步摘要任务。
     * <p>
     * 调用时机：会话锁内（每轮 chat 装配完视图后）。只做一次 count 查询 + 任务提交，
     * 不等待摘要执行，锁持有时间不受摘要 LLM 调用影响。
     */
    public void triggerIfNeeded(String conversationId, ConversationSummary segment) {
        if (segment == null || segment.getSegmentId() == null || segment.getStartMessageId() == null) {
            return;
        }
        try {
            int uncovered = messageMapper.countUncovered(conversationId,
                    segment.getStartMessageId(), segment.getCoveredMessageId());
            if (uncovered < properties.getHistory().getSummaryTriggerMessages()) {
                return;
            }
            int segmentId = segment.getSegmentId();
            summaryExecutor.execute(() -> summarizeLoop(conversationId, segmentId));
        } catch (Exception e) {
            // 计数失败/队列满丢弃：不阻断主流程，下轮重新触发
            log.warn("[summary] trigger failed, retry next round: {}", e.getMessage());
        }
    }

    /**
     * 异步任务：循环分批压缩，直到段内消息清空或达到批次上限。
     * 每批开始时重查段记录，取最新 covered_message_id，保证与并发触发互不干扰。
     */
    private void summarizeLoop(String conversationId, int segmentId) {
        int maxMessages = properties.getHistory().getSummaryMaxMessagesPerRun();
        int maxLength = properties.getHistory().getSummaryMaxLength();
        for (int loop = 0; loop < MAX_TASK_LOOPS; loop++) {
            ConversationSummary latest = findSegment(conversationId, segmentId);
            if (latest == null || latest.getStartMessageId() == null) {
                return;
            }
            List<Message> messages = messageMapper.selectUncovered(conversationId,
                    latest.getStartMessageId(), latest.getCoveredMessageId(), maxMessages);
            if (messages == null || messages.isEmpty()) {
                return;
            }
            try {
                String increment = generateIncrement(latest, messages);
                if (increment.isBlank()) {
                    // LLM 失败/空输出：不推进锚点，下轮重试（幂等）
                    log.warn("[summary] increment empty, keep anchor, retry next round, thread={}, segment={}",
                            conversationId, segmentId);
                    return;
                }
                String merged = merge(latest.getContent(), increment, maxLength);
                latest.setContent(merged);
                latest.setCoveredMessageId(messages.get(messages.size() - 1).getId());
                summaryMapper.upsertIncrement(latest);
                log.info("[summary] segment={} covered to message={}, contentLength={}",
                        segmentId, latest.getCoveredMessageId(), merged.length());
            } catch (Exception e) {
                log.warn("[summary] summarize batch failed, retry next round: {}", e.getMessage());
                return;
            }
            if (messages.size() < maxMessages) {
                // 本批已取完（不足一批说明已清空），结束任务
                return;
            }
        }
    }

    /** 调用 LLM 生成增量摘要：已有摘要作为上下文，输出只补新增要点 */
    private String generateIncrement(ConversationSummary segment, List<Message> messages) {
        StringBuilder sb = new StringBuilder("新增对话：\n");
        for (Message m : messages) {
            if (m == null || m.getContent() == null) {
                continue;
            }
            String content = m.getContent();
            if (content.length() > MAX_INPUT_ITEM_LENGTH) {
                content = content.substring(0, MAX_INPUT_ITEM_LENGTH) + "...[截断]";
            }
            sb.append(Message.ROLE_USER.equals(m.getRole()) ? "[用户] " : "[助手] ")
              .append(content).append('\n');
        }
        String userPrompt = "已有摘要：\n" + (segment.getContent().isBlank() ? "（无）" : segment.getContent())
                + "\n\n" + sb;
        String output = llmGateway.generate(properties.getLlm().getDefaultModel(),
                PromptTemplates.SUMMARY_SYSTEM, userPrompt).text();
        if (output == null) {
            return "";
        }
        return output.replaceAll("[\\p{Cntrl}]", " ").trim();
    }

    /** 合并旧摘要与增量，超长截断保头部（旧要点优先于新细节） */
    private String merge(String existing, String increment, int maxLength) {
        String merged = (existing == null ? "" : existing) + increment;
        if (merged.length() > maxLength) {
            return merged.substring(0, maxLength);
        }
        return merged;
    }

    private ConversationSummary findSegment(String conversationId, int segmentId) {
        List<ConversationSummary> segments = summaryMapper.selectByConversationId(conversationId);
        if (segments == null) {
            return null;
        }
        for (ConversationSummary s : segments) {
            if (s.getSegmentId() != null && s.getSegmentId() == segmentId) {
                return s;
            }
        }
        return null;
    }
}
