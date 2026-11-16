package com.example.langchain4j.dto.response;

import lombok.Data;

import java.util.List;
import java.util.Map;

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
    /** 回答引用来源（任务链路工具证据；无证据时为空列表） */
    private List<Citation> citations = List.of();
    /** 回答置信度 0~1（任务链路启发式评分；非任务轮为空） */
    private Double confidence;
    /** 置信度说明（证据条数/工具失败次数等） */
    private String confidenceReason = "";
    /** 工具调用轨迹（任务链路每一步的工具、参数、结果与耗时） */
    private List<ToolCallTrace> toolTrace = List.of();
    /** 本轮是否因缺参转为追问 */
    private boolean clarify;
    /** 写操作待确认信息（含一次性 confirmationId）；非写操作轮为 null */
    private PendingAction pendingAction;

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

    /** 带任务链路可观测字段的工厂：引用/置信度/工具轨迹/追问标记 */
    public static ChatResponse of(String conversationId, String result, boolean fallback,
                                  Integer tokenUsage, long costMs, boolean done, String rootCause,
                                  List<Citation> citations, Double confidence, String confidenceReason,
                                  List<ToolCallTrace> toolTrace, boolean clarify) {
        ChatResponse response = of(conversationId, result, fallback, tokenUsage, costMs, done, rootCause);
        response.citations = citations == null ? List.of() : List.copyOf(citations);
        response.confidence = confidence == null ? null : Math.max(0.0, Math.min(1.0, confidence));
        response.confidenceReason = confidenceReason == null ? "" : confidenceReason;
        response.toolTrace = toolTrace == null ? List.of() : List.copyOf(toolTrace);
        response.clarify = clarify;
        return response;
    }

    /** 引用来源（type=TOOL/KNOWLEDGE；sourceId 为订单号或知识块 ID） */
    public record Citation(String type, String sourceId, String title, String snippet, double score) {
    }

    /** 工具调用轨迹（args 为模型生成的参数快照） */
    public record ToolCallTrace(String tool, Map<String, Object> args, String purpose,
                                boolean success, String summary, String errorCode, long costMs) {
    }

    /** 写操作确认卡片：只暴露展示所需字段，不泄露内部用户/会话标识 */
    public record PendingAction(String confirmationId, String tool, Map<String, Object> args,
                                String summary, String risk, long expiresAt) {
    }
}
