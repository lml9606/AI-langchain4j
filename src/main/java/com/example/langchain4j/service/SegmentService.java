package com.example.langchain4j.service;

import com.example.langchain4j.repository.entity.ConversationSummary;
import com.example.langchain4j.repository.mapper.SummaryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 话题段服务：会话内话题段的归属决策与生命周期管理。
 * <p>
 * 归属规则（输入为改写器的 topic/newTopic 判定）：
 * <ol>
 *   <li>无段 → 创建第一段（首轮话题，topic 可能为空）；</li>
 *   <li>topic 精确命中已有段 → 复用（用户切回旧话题，生成视图与摘要只取该段）；</li>
 *   <li>最新段未命名且改写器给出话题名 → 复用最新段并补名（首轮内容不游离在段外）；</li>
 *   <li>newTopic → 新建段（话题漂移）；</li>
 *   <li>未判断/解析降级 → 兜底最新段。</li>
 * </ol>
 * 段内容由 {@link SummaryService} 异步填充，本服务只负责归属决策与段记录创建。
 */
@Slf4j
@Service
public class SegmentService {

    private final SummaryMapper summaryMapper;

    public SegmentService(SummaryMapper summaryMapper) {
        this.summaryMapper = summaryMapper;
    }

    /**
     * 查询会话已有话题段（段号升序）。
     *
     * @param conversationId 会话 ID
     * @return 话题段列表（可能为空列表）
     */
    public List<ConversationSummary> listSegments(String conversationId) {
        List<ConversationSummary> segments = summaryMapper.selectByConversationId(conversationId);
        return segments == null ? List.of() : segments;
    }

    /**
     * 话题段归属：改写器 topic 精确命中已有段 → 复用该段；最新段未命名且改写器给出
     * 话题名 → 复用并补名；newTopic → 新建段；未判断/解析降级 → 兜底最新段。
     * 复用旧段时生成视图与摘要自动只取该段内容。
     *
     * @param conversationId 会话 ID
     * @param segments       会话已有话题段列表（可为空，段号升序）
     * @param rewrite        改写结果（含 topic 与 newTopic 判定）
     * @param userMessageId  本轮用户消息自增 id（新建段时锚定起点）
     * @return 本轮消息归属的话题段（复用或新建）
     */
    public ConversationSummary resolveSegment(String conversationId, List<ConversationSummary> segments,
                                              RewriteResult rewrite, Long userMessageId) {
        String topic = rewrite.topic();
        // 1. 无段：创建第一段（首轮话题，topic 可能为空）
        if (segments == null || segments.isEmpty()) {
            return createSegment(conversationId, 1, topic, userMessageId);
        }
        // 2. topic 精确命中已有段 → 复用（用户切回旧话题）
        if (topic != null && !topic.isBlank()) {
            for (ConversationSummary s : segments) {
                if (topic.equals(s.getTopic())) {
                    log.info("[segment] switch back to topic segment, thread={}, segment={}, topic={}",
                            conversationId, s.getSegmentId(), topic);
                    return s;
                }
            }
        }
        // 3. 最新段尚未命名且改写器给出话题名 → 复用最新段并补名
        //    （首轮创建的段 topic 为空，补名后首轮内容纳入该段，不游离在段外）
        ConversationSummary latest = segments.get(segments.size() - 1);
        if (topic != null && !topic.isBlank()
                && (latest.getTopic() == null || latest.getTopic().isBlank())) {
            latest.setTopic(topic);
            // 补名走专用 updateTopic：只改 topic，与异步摘要任务的 content/锚点更新互不干扰
            summaryMapper.updateTopic(conversationId, latest.getSegmentId(), topic);
            log.info("[segment] name latest segment, thread={}, segment={}, topic={}",
                    conversationId, latest.getSegmentId(), topic);
            return latest;
        }
        // 4. 改写器判定为新话题 → 新建段（话题漂移）
        if (rewrite.newTopic()) {
            int next = segments.stream()
                    .mapToInt(s -> s.getSegmentId() == null ? 0 : s.getSegmentId())
                    .max().orElse(0) + 1;
            return createSegment(conversationId, next, topic, userMessageId);
        }
        // 5. 兜底：归属最新段（segmentId 最大）
        return latest;
    }

    /**
     * 新建话题段：start_message_id 锚定当前用户消息，content 为空等摘要任务异步填充。
     *
     * @param conversationId 会话 ID
     * @param segmentId      段号（已有段最大段号 +1）
     * @param topic          话题标签（可为空，后续轮补名）
     * @param startMessageId 段起点消息 id
     * @return 新建的段记录（已落库）
     */
    private ConversationSummary createSegment(String conversationId, int segmentId, String topic, Long startMessageId) {
        ConversationSummary summary = new ConversationSummary();
        summary.setConversationId(conversationId);
        summary.setSegmentId(segmentId);
        summary.setTopic(topic == null ? "" : topic);
        summary.setContent("");
        summary.setStartMessageId(startMessageId);
        summaryMapper.insert(summary);
        log.info("[segment] new topic segment created, thread={}, segment={}, topic={}",
                conversationId, segmentId, topic);
        return summary;
    }
}
