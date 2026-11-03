package com.example.langchain4j.service;

import com.example.langchain4j.common.exception.BizException;
import com.example.langchain4j.common.exception.ErrorCode;
import com.example.langchain4j.dto.FeedbackRequest;
import com.example.langchain4j.repository.entity.Feedback;
import com.example.langchain4j.repository.mapper.FeedbackMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 反馈服务：点赞/点踩落库与统计（运营闭环的信号入口）。
 * <p>
 * 点踩（DOWN）与审计表的检索根因（KNOWLEDGE_MISSING 等）交叉分析，
 * 即可定位“知识缺失 → 用户不满”的短板问题，驱动知识补录。
 */
@Slf4j
@Service
public class FeedbackService {

    private static final int DEFAULT_LIMIT = 50;
    private static final int MAX_LIMIT = 200;

    private final FeedbackMapper feedbackMapper;

    public FeedbackService(FeedbackMapper feedbackMapper) {
        this.feedbackMapper = feedbackMapper;
    }

    /** 提交反馈（rating 白名单校验；归属校验由 controller 完成） */
    @Transactional(rollbackFor = Exception.class)
    public long submit(FeedbackRequest request, String userId) {
        if (!Feedback.RATING_UP.equals(request.rating()) && !Feedback.RATING_DOWN.equals(request.rating())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "rating 仅支持 UP / DOWN");
        }
        Feedback feedback = new Feedback();
        feedback.setConversationId(request.conversationId());
        feedback.setUserId(userId);
        feedback.setMessageId(request.messageId());
        feedback.setRating(request.rating());
        feedback.setComment(request.comment());
        feedbackMapper.insert(feedback);
        log.info("[feedback] submitted, conversation={}, rating={}", request.conversationId(), request.rating());
        return feedback.getId();
    }

    /** 会话反馈列表（倒序） */
    public List<Feedback> listByConversation(String conversationId, int limit) {
        int bounded = Math.min(Math.max(limit, 1), MAX_LIMIT);
        List<Feedback> list = feedbackMapper.selectByConversationId(conversationId, bounded);
        return list == null ? List.of() : list;
    }

    public List<Feedback> listByConversation(String conversationId) {
        return listByConversation(conversationId, DEFAULT_LIMIT);
    }

    /** 点踩按天统计（近 N 天趋势，驱动知识治理报表） */
    public List<FeedbackMapper.DownStat> downStats(int days) {
        int bounded = Math.min(Math.max(days, 1), 90);
        List<FeedbackMapper.DownStat> stats = feedbackMapper.selectDownStats(bounded);
        return stats == null ? List.of() : stats;
    }
}
