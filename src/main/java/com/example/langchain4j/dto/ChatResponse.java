package com.example.langchain4j.dto;

import lombok.Data;

/**
 * 对话响应：图执行最终结果（保证非空，兜底路径也返回降级答案）。
 */
@Data
public class ChatResponse {

    /** 会话 ID（同时是 LangGraph threadId） */
    private String conversationId;
    /** 最终回答（保证非空，兜底路径也返回降级答案） */
    private String result;
    /** 是否走了兜底节点（业务可据此提示降级） */
    private boolean fallback;
    /** 本轮生成消耗的 token 总数（模型未返回时为空） */
    private Integer tokenUsage;
    /** 服务端处理耗时（毫秒，不含网络传输） */
    private long costMs;
    /** 本轮是否已闭环（正常与兜底路径均为 true；图执行异常时此对象不返回） */
    private boolean done;
    /** 检索根因（COMPLEX_QUERY=问题复杂 / EXPRESSION_ISSUE=表述不当 / KNOWLEDGE_MISSING=知识缺失；正常命中为空串） */
    private String rootCause;

    public static ChatResponse of(String conversationId, String result, boolean fallback,
                                  Integer tokenUsage, long costMs, boolean done, String rootCause) {
        ChatResponse response = new ChatResponse();
        response.conversationId = conversationId;
        response.result = result == null || result.isBlank() ? "抱歉，未能生成有效回答，请稍后重试。" : result;
        response.fallback = fallback;
        response.tokenUsage = tokenUsage;
        response.costMs = costMs;
        response.done = done;
        response.rootCause = rootCause == null ? "" : rootCause;
        return response;
    }
}
