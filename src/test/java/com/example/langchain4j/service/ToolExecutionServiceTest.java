package com.example.langchain4j.service;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.repository.mapper.ToolExecutionMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 工具台账单测：待确认落账、确认后接管、取消态拒绝执行。
 */
@ExtendWith(MockitoExtension.class)
class ToolExecutionServiceTest {

    @Mock
    private ToolExecutionMapper executionMapper;

    private ToolExecutionService service;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getTool().getConfirmation().setStaleSeconds(60);
        service = new ToolExecutionService(executionMapper, properties);
    }

    private ToolExecution execution(ToolExecution.Status status) {
        ToolExecution execution = new ToolExecution();
        execution.setExecutionId("cfm_1");
        execution.setUserId("demo");
        execution.setConversationId("c1");
        execution.setTool("complete_demo_record");
        execution.setArgsHash("hash-1");
        execution.setStatus(status);
        return execution;
    }

    @Test
    @DisplayName("记录待确认动作 → 成功返回 true")
    void recordPendingReturnsTrueWhenInserted() {
        ToolExecution pending = execution(ToolExecution.Status.PENDING);
        when(executionMapper.insertPending(pending)).thenReturn(1);

        assertThat(service.recordPending(pending)).isTrue();
        verify(executionMapper).insertPending(pending);
    }

    @Test
    @DisplayName("确认 ID 已存在 → 返回 false，由确认服务撤销 Redis key")
    void recordPendingReturnsFalseWhenDuplicate() {
        ToolExecution pending = execution(ToolExecution.Status.PENDING);
        when(executionMapper.insertPending(pending)).thenReturn(0);

        assertThat(service.recordPending(pending)).isFalse();
    }

    @Test
    @DisplayName("用户确认后从 PENDING 接管为 RUNNING")
    void pendingExecutionCanBeReclaimed() {
        ToolExecution running = execution(ToolExecution.Status.RUNNING);
        ToolExecution pending = execution(ToolExecution.Status.PENDING);
        when(executionMapper.insertRunning(running)).thenReturn(0);
        when(executionMapper.selectByExecutionId("demo", "cfm_1")).thenReturn(pending);
        when(executionMapper.tryReclaim("demo", "cfm_1", 60, "hash-1")).thenReturn(1);

        ToolExecutionService.BeginResult result = service.begin(running);

        assertThat(result.status()).isEqualTo(ToolExecutionService.BeginStatus.ACQUIRED);
        verify(executionMapper).tryReclaim("demo", "cfm_1", 60, "hash-1");
    }

    @Test
    @DisplayName("已取消的确认 ID → 冲突，不能再次执行")
    void cancelledExecutionIsRejected() {
        ToolExecution running = execution(ToolExecution.Status.RUNNING);
        ToolExecution cancelled = execution(ToolExecution.Status.CANCELLED);
        when(executionMapper.insertRunning(running)).thenReturn(0);
        when(executionMapper.selectByExecutionId("demo", "cfm_1")).thenReturn(cancelled);

        ToolExecutionService.BeginResult result = service.begin(running);

        assertThat(result.status()).isEqualTo(ToolExecutionService.BeginStatus.CONFLICT);
        verify(executionMapper, never()).tryReclaim(anyString(), anyString(), anyInt(), anyString());
    }
}
