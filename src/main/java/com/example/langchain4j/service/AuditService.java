package com.example.langchain4j.service;

import com.example.langchain4j.audit.AuditEntry;
import com.example.langchain4j.audit.AuditRecorder;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.GraphExecution;
import com.example.langchain4j.repository.mapper.GraphExecutionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.UUID;

/**
 * 图执行审计服务：审计记录落库（可观测性数据，失败仅告警不阻断主流程）。
 *
 * <p>审计范围：图内节点、图外步骤（rewrite/retrieval-plan）、整体图执行（workflow）
 * 与工具调用，共用同一张表按 nodeName 区分。executionId 优先取请求 traceId，
 * 同一次请求的多条审计可以按 executionId 串起来。
 */
@Slf4j
@Service
public class AuditService implements AuditRecorder {

    private final GraphExecutionMapper graphExecutionMapper;
    private final AgentProperties properties;

    public AuditService(GraphExecutionMapper graphExecutionMapper, AgentProperties properties) {
        this.graphExecutionMapper = graphExecutionMapper;
        this.properties = properties;
    }

    /** 统一审计入口：同步落库，异常降级为告警。 */
    @Override
    public void record(AuditEntry entry) {
        try {
            GraphExecution execution = new GraphExecution();
            execution.setExecutionId(executionId(entry));
            execution.setConversationId(entry.conversationId());
            execution.setNodeName(entry.nodeName());
            execution.setStatus(entry.status());
            execution.setInputJson(truncate(JsonUtil.toJson(entry.input())));
            execution.setOutputJson(truncate(JsonUtil.toJson(entry.output())));
            execution.setErrorMsg(entry.errorMsg());
            execution.setCostMs(entry.costMs());
            execution.setRootCause(entry.rootCause());
            graphExecutionMapper.insert(execution);
        } catch (Exception e) {
            log.warn("[audit] audit insert failed, degrade: {}", e.getMessage());
        }
    }

    /** AuditEntry 已填充 traceId；极端情况下仍为空时用随机 ID 兜底。 */
    private String executionId(AuditEntry entry) {
        String id = entry.executionId();
        return id == null || id.isBlank()
                ? UUID.randomUUID().toString().replace("-", "") : id;
    }

    /** 审计 JSON 超长截断（保留头部 + 截断标记），防单条审计记录撑爆 DB 字段 */
    private String truncate(String json) {
        int maxLength = properties.getLimits().getAuditJsonMaxLength();
        if (json == null || json.length() <= maxLength) {
            return json;
        }
        return json.substring(0, maxLength) + "...[truncated]";
    }
}
