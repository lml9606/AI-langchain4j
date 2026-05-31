package com.example.langchain4j.service.persistence;

import com.example.langchain4j.entity.ChatMessage;
import com.example.langchain4j.entity.ChatSession;
import com.example.langchain4j.enums.UserActivityStatus;
import com.example.langchain4j.mapper.ChatMessageMapper;
import com.example.langchain4j.mapper.ChatSessionMapper;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * @Description :
 * @Reference :
 * @Author :
 * @CreateDate : 2026-05-31 16:08
 * @Modify:
 **/
@Service
public class ChatPersistenceService {

    @Resource
    private ChatMessageMapper messageMapper;

    @Resource
    private ChatSessionMapper sessionMapper;

    public void saveUserMessage(String userId, String content) {


        //获取最大序号
        int nextOrder = messageMapper.getMaxMessageOrder(userId) + 1;

        //插入消息
        ChatMessage chatMessage = new ChatMessage();
        chatMessage.setUserId(userId);
        chatMessage.setContent(content);
        chatMessage.setRole("User");
        chatMessage.setMessageOrder(nextOrder);
        chatMessage.setSummarized(false);
        chatMessage.setCreateTime(LocalDateTime.now());
        messageMapper.insert(chatMessage);

        ChatSession session = getOrCreateSession(userId);
        session.setMessageCount(session.getMessageCount() + 1);

        if (session.getTitle() == null && nextOrder == 1) {
            session.setTitle(content);
        }

        sessionMapper.updateById(session);


    }

    private ChatSession getOrCreateSession(String userId) {
        ChatSession activitySession = sessionMapper.selectByUserIdAndStatus(userId, UserActivityStatus.ACTIVE.getValue());

        if (activitySession == null) {
            activitySession = new ChatSession();
            activitySession.setUserId(userId);
            activitySession.setStatus(UserActivityStatus.ACTIVE.getValue());
            activitySession.setCreateTime(LocalDateTime.now());
            activitySession.setActivityTime(LocalDateTime.now());
            sessionMapper.insert(activitySession);
        }
        return activitySession;
    }

    public ChatSession getSession(String userId) {
        return sessionMapper.selectByUserIdAndStatus(userId, UserActivityStatus.ACTIVE.getValue());
    }

    public Integer countBySessionIdAndSummarizedFalse(Long sessionId) {
        return messageMapper.countBySessionIdAndSummarizedFalse(sessionId);
    }

    public List<ChatMessage>  getMessageBySummarize(Long sessionId,int limit) {

        return messageMapper.getMessageBySummarizeOrderMessageOrderAsc(sessionId,limit);

    }

}
