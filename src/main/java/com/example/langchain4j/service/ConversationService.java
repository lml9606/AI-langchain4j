package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.repository.entity.Conversation;
import com.example.langchain4j.repository.entity.Message;
import com.example.langchain4j.repository.mapper.ConversationMapper;
import com.example.langchain4j.repository.mapper.MessageMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
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

    /** 持久化一条消息（用户/助手），失败向上抛由全局异常处理转 DB_UNAVAILABLE */
    @Transactional(rollbackFor = Exception.class)
    public void saveMessage(String conversationId, String role, String content,
                            String model, Integer tokenUsage) {
        Message message = new Message();
        message.setConversationId(conversationId);
        message.setRole(role);
        message.setContent(content == null ? "" : content);
        message.setModel(model);
        message.setTokenUsage(tokenUsage);
        messageMapper.insert(message);
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
}
