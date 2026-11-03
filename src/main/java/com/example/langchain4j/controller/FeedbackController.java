package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.dto.FeedbackRequest;
import com.example.langchain4j.repository.entity.Feedback;
import com.example.langchain4j.repository.mapper.FeedbackMapper;
import com.example.langchain4j.service.ConversationService;
import com.example.langchain4j.service.FeedbackService;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 反馈接入层：点赞/点踩提交与统计（运营闭环信号入口）。
 */
@RestController
@RequestMapping("/api/feedback")
public class FeedbackController {

    private final FeedbackService feedbackService;
    private final ConversationService conversationService;

    public FeedbackController(FeedbackService feedbackService, ConversationService conversationService) {
        this.feedbackService = feedbackService;
        this.conversationService = conversationService;
    }

    /** 提交反馈（会话归属校验：只能反馈自己的会话） */
    @PostMapping
    public ApiResponse<Map<String, Object>> submit(@Valid @RequestBody FeedbackRequest request) {
        String userId = UserContext.require();
        conversationService.requireOwnership(request.conversationId(), userId);
        return ApiResponse.success(Map.of("id", feedbackService.submit(request, userId)));
    }

    /** 会话反馈列表 */
    @GetMapping("/conversation/{id}")
    public ApiResponse<List<Feedback>> listByConversation(@PathVariable("id") String conversationId) {
        String userId = UserContext.require();
        conversationService.requireOwnership(conversationId, userId);
        return ApiResponse.success(feedbackService.listByConversation(conversationId));
    }

    /** 点踩按天统计（近 N 天趋势，运营视角） */
    @GetMapping("/down-stats")
    public ApiResponse<List<FeedbackMapper.DownStat>> downStats(@RequestParam(defaultValue = "7") int days) {
        return ApiResponse.success(feedbackService.downStats(days));
    }
}
