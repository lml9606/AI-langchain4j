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
 * 重写式摘要：每次把「已有摘要 + 新增对话」一起交给 LLM 重写为完整摘要（非增量拼接），
 * 天然去重、总长可控，避免拼接式滚雪球。
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
    /** 摘要输出最短长度下限：低于视为异常输出（LLM 返回垃圾），拒绝采纳防覆盖丢要点 */
    private static final int MIN_REFINED_LENGTH = 50;
    /** 缩水比例：重写结果短于已有摘要的 1/N 视为异常（正常重写合并新增后不应显著变短） */
    private static final int MIN_RETAIN_RATIO = 3;

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
     *
     * @param conversationId 会话 ID
     * @param segment        本轮消息归属的话题段（取段号与锚点用于计数，可能未命名）
     */
    public void triggerIfNeeded(String conversationId, ConversationSummary segment) {
        if (segment == null || segment.getSegmentId() == null || segment.getStartMessageId() == null) {
            return;
        }
        try {
            int uncovered = messageMapper.countUncovered(conversationId,
                    segment.getSegmentId(), segment.getCoveredMessageId());
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
     * <p>
     * 幂等：任一批失败/输出异常都不推进锚点，直接结束任务，下轮聊天重新触发续跑。
     *
     * @param conversationId 会话 ID
     * @param segmentId      待压缩的话题段号
     */
    private void summarizeLoop(String conversationId, int segmentId) {
        int maxMessages = properties.getHistory().getSummaryMaxMessagesPerRun();
        int maxLength = properties.getHistory().getSummaryMaxLength();
        for (int loop = 0; loop < MAX_TASK_LOOPS; loop++) {
            ConversationSummary latest = findSegment(conversationId, segmentId);
            if (latest == null || latest.getStartMessageId() == null) {
                return;
            }
            List<Message> messages = messageMapper.selectUncovered(conversationId, segmentId,
                    latest.getCoveredMessageId(), maxMessages);
            if (messages == null || messages.isEmpty()) {
                return;
            }
            try {
                // 重写式：LLM 把已有摘要 + 本批新增对话合并重写为完整摘要
                String refined = generateRefined(latest, messages);
                if (refined.isBlank()) {
                    // LLM 失败/空输出：不推进锚点，下轮重试（幂等）
                    log.warn("[summary] refined empty, keep anchor, retry next round, thread={}, segment={}",
                            conversationId, segmentId);
                    return;
                }
                // 质量下限校验：过短/比已有摘要显著缩水视为异常输出，拒绝采纳
                // （重写式直接覆盖 content，垃圾输出会丢旧要点且锚点推进后不可逆）
                if (isSuspicious(refined, latest.getContent())) {
                    log.warn("[summary] refined suspicious, keep anchor, retry next round, thread={}, segment={}, length={}",
                            conversationId, segmentId, refined.length());
                    return;
                }
                // 超长防御：LLM 不遵守长度约束时截断（重写式正常不会触发，仅兜底）
                if (refined.length() > maxLength) {
                    refined = refined.substring(0, maxLength);
                }
                latest.setContent(refined);
                latest.setCoveredMessageId(messages.get(messages.size() - 1).getId());
                summaryMapper.upsertIncrement(latest);
                log.info("[summary] segment={} covered to message={}, contentLength={}",
                        segmentId, latest.getCoveredMessageId(), refined.length());
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

    /**
     * 调用 LLM 重写完整摘要：已有摘要作为上下文，与新增对话合并重写（非增量），
     * 输出为去重后的完整摘要，由调用方做超长防御截断。
     *
     * @param segment  当前段记录（取其已有摘要 content 作为重写上下文）
     * @param messages 本批待压缩消息（正序，单条超 {@link #MAX_INPUT_ITEM_LENGTH} 字截断）
     * @return 重写后的完整摘要文本（控制字符已清洗）；LLM 失败时返回空串
     */
    private String generateRefined(ConversationSummary segment, List<Message> messages) {
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

    /**
     * 质量下限校验：重写式摘要过短（LLM 返回垃圾）或比已有摘要显著缩水（丢要点）
     * 视为异常输出，拒绝采纳 —— 配合调用方锚点不推进，下轮重试。
     *
     * @param refined  本批 LLM 重写输出的候选摘要
     * @param existing 段内已有摘要（首批为空串时只做最短长度校验）
     * @return true=异常输出应拒绝采纳；false=通过校验可覆盖
     */
    private boolean isSuspicious(String refined, String existing) {
        if (refined.length() < MIN_REFINED_LENGTH) {
            return true;
        }
        return existing != null && !existing.isBlank()
                && refined.length() < existing.length() / MIN_RETAIN_RATIO;
    }

    /**
     * 查单段记录：按会话 ID + 段号单行查询（每批开始重查，取最新锚点，
     * 防与并发触发/多任务交错时用旧锚点重复压）。
     *
     * @param conversationId 会话 ID
     * @param segmentId      话题段号
     * @return 段记录；不存在时返回 null（调用方直接结束任务）
     */
    private ConversationSummary findSegment(String conversationId, int segmentId) {
        return summaryMapper.selectBySegment(conversationId, segmentId);
    }
}
