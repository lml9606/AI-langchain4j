package com.example.langchain4j.service;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.repository.mapper.GraphExecutionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

/**
 * 图执行审计服务：节点级执行记录落库（可观测性数据，失败仅告警不阻断主流程）。
 * <p>
 * 审计范围：图内节点（由流式事件迭代器逐节点写入）与图外步骤（如 "rewrite"）、
 * 整体图执行（"workflow"），共用同一张表按 nodeName 区分。
 */
@Slf4j
@Service
public class AuditService {

    /** 审计 JSON 最大长度：超出截断，防单条记录撑爆 DB 字段 */
    private static final int MAX_AUDIT_JSON_LENGTH = 4000;

    private final GraphExecutionMapper graphExecutionMapper;

    public AuditService(GraphExecutionMapper graphExecutionMapper) {
        this.graphExecutionMapper = graphExecutionMapper;
    }

    /**
     * 审计落库（失败仅告警，不阻断主流程）。
     *
     * @param conversationId 会话 ID（threadId）
     * @param nodeName       节点名（图外步骤如 "rewrite" 也复用此字段）
     * @param status         执行状态（GraphExecution.STATUS_*）
     * @param input          输入摘要（写入 inputJson）
     * @param output         输出摘要（写入 outputJson）
     * @param errorMsg       错误信息（成功时为空）
     * @param costMs         耗时（毫秒，可为空）
     */
    public void audit(String conversationId, String nodeName, String status,
                      String input, String output, String errorMsg, Long costMs) {
        try {
            GraphExecution execution = new GraphExecution();
            execution.setExecutionId(UUID.randomUUID().toString().replace("-", ""));
            execution.setConversationId(conversationId);
            execution.setNodeName(nodeName);
            execution.setStatus(status);
            execution.setInputJson(truncate(JsonUtil.toJson(Map.of("query", input == null ? "" : input))));
            execution.setOutputJson(truncate(JsonUtil.toJson(Map.of("result", output == null ? "" : output))));
            execution.setErrorMsg(errorMsg);
            execution.setCostMs(costMs);
            graphExecutionMapper.insert(execution);
        } catch (Exception e) {
            log.warn("[audit] audit insert failed, degrade: {}", e.getMessage());
        }
    }

    /** 审计 JSON 超长截断（保留头部 + 截断标记），防单条审计记录撑爆 DB 字段 */
    private String truncate(String json) {
        if (json == null || json.length() <= MAX_AUDIT_JSON_LENGTH) {
            return json;
        }
        return json.substring(0, MAX_AUDIT_JSON_LENGTH) + "...[truncated]";
    }
}
