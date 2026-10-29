package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.common.util.TokenEstimator;
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

    private static final int TITLE_MAX_LENGTH = 30;
    private static final int DEFAULT_MESSAGE_LIMIT = 50;
    private static final int MAX_MESSAGE_LIMIT = 200;

    private final ConversationMapper conversationMapper;
    private final MessageMapper messageMapper;

    public ConversationService(ConversationMapper conversationMapper, MessageMapper messageMapper) {
        this.conversationMapper = conversationMapper;
        this.messageMapper = messageMapper;
    }

    /**
     * 会话存在则复用，否则新建。
     * 会话 ID 同时作为 LangGraph 的 threadId（checkpoint 会话隔离）。
     */
    @Transactional(rollbackFor = Exception.class)
    public String ensureConversation(String userId, String conversationId, String firstQuery) {
        if (conversationId != null && !conversationId.isBlank()) {
            Conversation existing = conversationMapper.selectByConversationId(conversationId);
            if (existing != null) {
                return existing.getConversationId();
            }
            // 传入的会话不存在：按新会话处理（防御脏参数）
            log.warn("[conversation] conversationId not found, create new: {}", conversationId);
        }
        Conversation conversation = new Conversation();
        conversation.setConversationId(UUID.randomUUID().toString().replace("-", ""));
        conversation.setUserId(userId);
        conversation.setTitle(deriveTitle(firstQuery));
        conversation.setStatus(Conversation.STATUS_ACTIVE);
        conversationMapper.insert(conversation);
        return conversation.getConversationId();
    }

    /**
     * 持久化一条消息（用户/助手），失败向上抛由全局异常处理转 DB_UNAVAILABLE。
     * 返回落库后的消息（含数据库回填的自增 id，供话题段起点锚定）。
     * <p>
     * 段归属规则：user 消息落库时暂不带段号（段归属决策在其后，由
     * {@link #updateMessageSegment} 回填）；assistant 消息自动继承同轮 user 的段号
     * （同轮 user 已在锁内回填），resume 恢复轮同理。
     */
    @Transactional(rollbackFor = Exception.class)
    public Message saveMessage(String conversationId, String role, String content,
                               String model, Integer tokenUsage) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setModel(model);
        message.setTokenUsage(tokenUsage);
        if (Message.ROLE_ASSISTANT.equals(role)) {
            message.setSegmentId(messageMapper.selectLatestUserSegmentId(conversationId));
        }
        messageMapper.insert(message);
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

    /** 查询会话历史消息（倒序取 limit 条） */
    public List<Message> listMessages(String conversationId, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_MESSAGE_LIMIT);
        List<Message> messages = messageMapper.selectByConversationId(conversationId, bounded);
        if (messages == null) {
            return List.of();
        }
        return messages;
    }

    public List<Message> listMessages(String conversationId) {
        return listMessages(conversationId, DEFAULT_MESSAGE_LIMIT);
    }

    /**
     * 改写视图：跨话题取最近 limit 条历史（正序，role/content 键值对），排除当前刚保存的用户消息。
     * <p>
     * 【只给图外改写器用】（指代消解 + 话题判断），不进生成 LLM。指代判断需要最近的
     * 对话流（可能跨话题），与生成视图（当前话题段内原文）是两条独立路径，互不混用。
     */
    public List<Map<String, String>> rewriteHistoryView(String conversationId, int limit, int itemMaxLength) {
        int bounded = Math.min(Math.max(limit, 1), MAX_MESSAGE_LIMIT);
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
            history.add(Map.of(
                    "role", m.getRole() == null ? "" : m.getRole(),
                    "content", truncate(m.getContent(), itemMaxLength)));
        }
        return history;
    }

    /**
     * 生成视图：取当前话题段内最近的消息（正序，role/content 键值对，按段号过滤），
     * 按 token 预算从最新往回装配，排除当前刚保存的用户消息。
     * <p>
     * 【只给图内生成 LLM 用】（原文回放）。按段号精确过滤：切回旧话题只带该段原文；
     * 新话题首轮历史为空 —— 其他话题的消息一条都不带。
     */
    public List<Map<String, String>> generationHistoryView(String conversationId, int segmentId,
                                                           int maxTokens, int itemMaxLength,
                                                           int candidateLimit) {
        int bounded = Math.min(Math.max(candidateLimit, 1), MAX_MESSAGE_LIMIT);
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

    /** 从首条 query 派生会话标题（截断 + 控制字符清洗） */
    private String deriveTitle(String firstQuery) {
        if (firstQuery == null || firstQuery.isBlank()) {
            return "新会话";
        }
        String cleaned = firstQuery.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (cleaned.length() <= TITLE_MAX_LENGTH) {
            return cleaned;
        }
        return cleaned.substring(0, TITLE_MAX_LENGTH) + "...";
    }

    /** 历史单条消息超长截断（保留头部，防御超长历史撑爆上下文窗口） */
    private String truncate(String content, int maxLength) {
        if (content.length() <= maxLength) {
            return content;
        }
        return content.substring(0, maxLength) + "...[截断]";
    }
}
