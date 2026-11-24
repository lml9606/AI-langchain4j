package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.TokenEstimator;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.Conversation;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.mapper.ConversationMapper;
import com.example.langchain4j.repository.mapper.MessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 会话服务：会话与消息的持久化边界（MyBatis 事务在 service 层）。
 */
@Slf4j
@Service
public class ConversationService {

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;
    private final AgentProperties properties;

    public ConversationService(ConversationMapper conversationMapper, MessageMapper messageMapper,
                               AgentProperties properties) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
        this.properties = properties;
    }

    /**
     * 会话存在则复用，否则新建。
     * 会话 ID 同时作为 LangGraph 的 threadId（checkpoint 会话隔离）。
     *
     * 用户隔离：传入会话已存在但归属他人时拒绝（403，防水平越权）；
     * 传入不存在的会话 ID 按新会话处理（防御脏参数，不泄露资源存在性）。
     */
    @Transactional(rollbackFor = Exception.class)
    public String ensureConversation(String userId, String conversationId, String firstQuery) {
        String requestedId = conversationId == null || conversationId.isBlank()
                ? null : conversationId;
        if (requestedId != null) {
            Conversation existing = conversationMapper.selectByConversationId(requestedId);
            if (existing != null) {
                if (!userId.equals(existing.getUserId())) {
                    log.warn("[conversation] forbid cross-user access, conversation={}, user={}",
                            requestedId, userId);
                    throw new BizException(ErrorCode.FORBIDDEN, "无权访问该会话");
                }
                return existing.getConversationId();
            }
            // 外部传入了会话 ID 但库里没有：用同一个 ID 幂等补建，避免每次请求都新建会话导致历史丢失。
            log.warn("[conversation] conversationId not found, bootstrap with same id: {}",
                    requestedId);
        }
        Conversation conversation = new Conversation();
        conversation.setConversationId(requestedId != null ? requestedId
                : UUID.randomUUID().toString().replace("-", ""));
        conversation.setUserId(userId);
        conversation.setTitle(deriveTitle(firstQuery));
        conversation.setStatus(Conversation.STATUS_ACTIVE);
        conversationMapper.insert(conversation);
        // 并发补建时另一请求可能先插入；ON CONFLICT + 重查保证同一 ID 只对应一个会话。
        Conversation persisted = conversationMapper.selectByConversationId(conversation.getConversationId());
        if (persisted != null) {
            if (!userId.equals(persisted.getUserId())) {
                log.warn("[conversation] concurrent owner conflict, conversation={}, user={}",
                        persisted.getConversationId(), userId);
                throw new BizException(ErrorCode.FORBIDDEN, "无权访问该会话");
            }
            return persisted.getConversationId();
        }
        return conversation.getConversationId();
    }

    /**
     * 持久化一条消息（用户/助手），失败向上抛由全局异常处理转 DB_UNAVAILABLE。
     * 返回落库后的消息（含数据库回填的自增 id，供话题段起点锚定）。
     *
     * 段归属规则：user 消息落库时暂不带段号（段归属决策在其后，由
     * {@link #updateMessageSegment} 回填）；assistant 消息自动继承同轮 user 的段号
     * （同轮 user 已在锁内回填），resume 恢复轮同理。
     */
    @Transactional(rollbackFor = Exception.class)
    public Message saveMessage(String conversationId, String role, String content,
                               String model, Integer tokenUsage) {
        return saveMessage(conversationId, role, content, model, tokenUsage, null);
    }

    /**
     * 持久化一条消息（含检索上下文快照）。
     *
     * @param context 检索上下文快照（仅 assistant 消息：本轮的检索资料原文截断；
     *                user 消息传 null。仅供排查取证，不替代真实回答参与改写）
     */
    @Transactional(rollbackFor = Exception.class)
    public Message saveMessage(String conversationId, String role, String content,
                               String model, Integer tokenUsage, String context) {
        return saveMessage(conversationId, role, content, model, tokenUsage, context, null);
    }

    /**
     * 带幂等键的消息写入：唯一键冲突时返回已存在的同键消息。
     * 用于需要消息级幂等的补偿场景。Redis 会话锁 fail-open、多个实例同时补偿时，
     * 数据库唯一键仍能保证同一任务最多产生一条回复。
     */
    @Transactional(rollbackFor = Exception.class)
    public Message saveMessage(String conversationId, String role, String content,
                               String model, Integer tokenUsage, String context,
                               String dedupeKey) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setModel(model);
        message.setTokenUsage(tokenUsage);
        message.setContext(context);
        String normalizedKey = dedupeKey == null || dedupeKey.isBlank() ? null : dedupeKey.trim();
        message.setDedupeKey(normalizedKey);
        if (Message.ROLE_ASSISTANT.equals(role)) {
            message.setSegmentId(messageMapper.selectLatestUserSegmentId(conversationId));
        }
        messageMapper.insert(message);
        if (normalizedKey != null && message.getId() == null) {
            Message persisted = messageMapper.selectByDedupeKey(normalizedKey);
            if (persisted == null) {
                throw new BizException(ErrorCode.DB_UNAVAILABLE, "消息幂等写入失败");
            }
            return persisted;
        }
        return message;
    }

    /** 回填消息的话题段归属（user 消息落库 → 段归属决策 → 回填段号） */
    public void updateMessageSegment(Long messageId, int segmentId) {
        messageMapper.updateSegmentId(messageId, segmentId);
    }

    /** 最后一轮是否已闭环：最新一条消息是 assistant（含超时降级轮）；无消息视为已闭环 */
    public boolean isLastTurnClosed(String conversationId) {
        List<Message> latest = messageMapper.selectRecentByConversationId(conversationId, 1);
        if (latest == null || latest.isEmpty()) {
            return true;
        }
        return Message.ROLE_ASSISTANT.equals(latest.get(0).getRole());
    }

    /** 最近一条消息；恢复补偿用它判断“确认轮是否仍在等待助手回复”。 */
    public Message latestMessage(String conversationId) {
        List<Message> latest = messageMapper.selectRecentByConversationId(conversationId, 1);
        return latest == null || latest.isEmpty() ? null : latest.get(0);
    }

    /** 查询会话历史消息（倒序取 limit 条） */
    public List<Message> listMessages(String conversationId, int limit) {
        int bounded = Math.min(Math.max(limit, 1), properties.getLimits().getMaxMessageLimit());
        List<Message> messages = messageMapper.selectByConversationId(conversationId, bounded);
        if (messages == null) {
            return List.of();
        }
        return messages;
    }

    public List<Message> listMessages(String conversationId) {
        return listMessages(conversationId, properties.getLimits().getDefaultMessageLimit());
    }

    /**
     * 改写视图：跨话题取最近 limit 条历史（正序，role/content 键值对），排除当前刚保存的用户消息。
     *
     * 【只给图外改写器用】（指代消解 + 话题判断），不进生成 LLM。指代判断需要最近的
     * 对话流（可能跨话题），与生成视图（当前话题段内原文）是两条独立路径，互不混用。
     */
    public List<Map<String, String>> rewriteHistoryView(String conversationId, int limit, int itemMaxLength) {
        int bounded = Math.min(Math.max(limit, 1), properties.getLimits().getMaxMessageLimit());
        // 多取一条：最近 N+1 条的最后一条即当前刚保存的用户消息，取完排除，避免与 query 重复注入
        List<Message> messages = messageMapper.selectRecentByConversationId(conversationId, bounded + 1);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        List<Map<String, String>> history = new ArrayList<>(Math.max(messages.size() - 1, 0));
        for (int i = 0; i < messages.size() - 1; i++) {
            Message m = messages.get(i);
            if (m == null || m.getContent() == null) {
                continue;
            }
            // 改写需要上一轮回答来解析“上一轮/上面/详细说说”等指代；检索快照不是对话内容，
            // 不能替代 assistant 回答，否则用户追问上一轮答案时会被改写成无关问题。
            String content = m.getContent();
            history.add(Map.of(
                    "role", m.getRole() == null ? "" : m.getRole(),
                    "content", truncate(content, itemMaxLength)));
        }
        return history;
    }

    /**
     * 生成视图：取当前话题段内最近的消息（正序，role/content 键值对，按段号过滤），
     * 按 token 预算从最新往回装配，排除当前刚保存的用户消息。
     *
     * 【只给图内生成 LLM 用】（原文回放）。按段号精确过滤：切回旧话题只带该段原文；
     * 新话题首轮历史为空 —— 其他话题的消息一条都不带。
     */
    public List<Map<String, String>> generationHistoryView(String conversationId, int segmentId,
                                                           int maxTokens, int itemMaxLength,
                                                           int candidateLimit) {
        int bounded = Math.min(Math.max(candidateLimit, 1), properties.getLimits().getMaxMessageLimit());
        List<Message> messages = messageMapper.selectRecentBySegment(conversationId, segmentId, bounded);
        if (messages == null || messages.isEmpty()) {
            return List.of();
        }
        // 倒序累计 token：最后一条是当前用户消息（与 query 重复），跳过；预算耗尽即停
        Deque<Map<String, String>> selected = new ArrayDeque<>();
        int tokens = 0;
        for (int i = messages.size() - 2; i >= 0; i--) {
            Message m = messages.get(i);
            if (m == null || m.getContent() == null) {
                continue;
            }
            String content = truncate(m.getContent(), itemMaxLength);
            int cost = TokenEstimator.estimate(content);
            if (!selected.isEmpty() && tokens + cost > maxTokens) {
                break;
            }
            selected.addFirst(Map.of(
                    "role", m.getRole() == null ? "" : m.getRole(),
                    "content", content));
            tokens += cost;
        }
        return new ArrayList<>(selected);
    }

    public Conversation getConversation(String conversationId) {
        Conversation conversation = conversationMapper.selectByConversationId(conversationId);
        if (conversation == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "会话不存在: " + conversationId);
        }
        return conversation;
    }

    /**
     * 校验会话归属当前用户：会话不存在抛 404，归属他人抛 403（水平越权防御）。
     * 用于历史/断点/状态等按会话 ID 直查的接口（chat 入口的校验在 ensureConversation 内）。
     */
    public void requireOwnership(String conversationId, String userId) {
        Conversation conversation = getConversation(conversationId);
        if (!userId.equals(conversation.getUserId())) {
            log.warn("[conversation] forbid cross-user access, conversation={}, user={}", conversationId, userId);
            throw new BizException(ErrorCode.FORBIDDEN, "无权访问该会话");
        }
    }

    /** 从首条 query 派生会话标题（截断 + 控制字符清洗） */
    private String deriveTitle(String firstQuery) {
        if (firstQuery == null || firstQuery.isBlank()) {
            return "新会话";
        }
        String cleaned = firstQuery.replaceAll("[\\p{Cntrl}]", " ").trim();
        int titleMaxLength = properties.getLimits().getConversationTitleMaxLength();
        if (cleaned.length() <= titleMaxLength) {
            return cleaned;
        }
        return cleaned.substring(0, titleMaxLength) + "...";
    }

    /** 历史单条消息超长截断（保留头部，防御超长历史撑爆上下文窗口） */
    private String truncate(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...[截断]";
    }
}
