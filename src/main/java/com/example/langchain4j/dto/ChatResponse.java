package com.example.langchain4j.dto;

import lombok.Data;

/**
 * 对话响应：图执行最终结果（保证非空，兜底路径也返回降级答案）。
 */
@Data
public class ChatResponse {

    private String conversationId;
    private String result;
    /** 是否走了兜底节点（业务可据此提示降级） */
    private boolean fallback;
    private Integer tokenUsage;
    private long costMs;
    private boolean done;

    public static ChatResponse of(String conversationId, String result, boolean fallback,
                                  Integer tokenUsage, long costMs, boolean done) {
        ChatResponse response = new ChatResponse();
        response.conversationId = conversationId;
        response.result = result == null || result.isBlank() ? "抱歉，未能生成有效回答，请稍后重试。" : result;
        response.fallback = fallback;
        response.tokenUsage = tokenUsage;
        response.costMs = costMs;
        response.done = done;
        return response;
    }
}
