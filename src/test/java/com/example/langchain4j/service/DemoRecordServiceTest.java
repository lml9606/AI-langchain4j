package com.example.langchain4j.service;

import com.example.langchain4j.repository.entity.ToolDemoRecord;
import com.example.langchain4j.repository.entity.ToolExecution;
import com.example.langchain4j.repository.mapper.ToolDemoRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写工具幂等单测：重放、执行中、参数冲突、越权与已完成状态。
 */
@ExtendWith(MockitoExtension.class)
class DemoRecordServiceTest {

    @Mock
    private ToolDemoRecordMapper recordMapper;
    @Mock
    private ToolExecutionService executionService;

    private DemoRecordService service;

    @BeforeEach
    void setUp() {
        service = new DemoRecordService(recordMapper, executionService);
    }

    private ToolDemoRecord record(String status) {
        ToolDemoRecord record = new ToolDemoRecord();
        record.setId(1L);
        record.setUserId("demo");
        record.setTitle("演示任务 A");
        record.setStatus(status);
        return record;
    }

    @Test
    @DisplayName("首次执行：业务写 + 台账成功")
    void firstExecutionWritesAndSucceeds() {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(ToolExecutionService.BeginStatus.ACQUIRED, null));
        when(recordMapper.selectById(1L, "demo")).thenReturn(record(ToolDemoRecord.STATUS_OPEN));
        when(recordMapper.markDone(1L, "demo")).thenReturn(1);

        DemoRecordService.CompleteOutcome outcome =
                service.complete("demo", "c1", "cfm_1", "complete_demo_record", 1L);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.result()).contains("标记为完成");
        verify(executionService).succeed(eq("demo"), eq("cfm_1"), anyString());
    }

    @Test
    @DisplayName("重复确认：命中台账成功 → 原样返回上次结果，不再执行业务写")
    void replayReturnsStoredResult() {
        ToolExecution existing = new ToolExecution();
        existing.setStatus(ToolExecution.STATUS_SUCCEEDED);
        existing.setResult("已将记录 1 标记为完成");
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(ToolExecutionService.BeginStatus.REPLAY, existing));

        DemoRecordService.CompleteOutcome outcome =
                service.complete("demo", "c1", "cfm_1", "complete_demo_record", 1L);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.result()).isEqualTo("已将记录 1 标记为完成");
        verify(recordMapper, never()).markDone(anyLong(), anyString());
    }

    @Test
    @DisplayName("记录已经是 DONE：按业务幂等返回成功，不重复修改")
    void alreadyDoneIsIdempotentSuccess() {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(ToolExecutionService.BeginStatus.ACQUIRED, null));
        when(recordMapper.selectById(1L, "demo")).thenReturn(record(ToolDemoRecord.STATUS_DONE));

        DemoRecordService.CompleteOutcome outcome =
                service.complete("demo", "c1", "cfm_1", "complete_demo_record", 1L);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.result()).contains("已经是完成状态");
        verify(recordMapper, never()).markDone(anyLong(), anyString());
    }

    @Test
    @DisplayName("越权/不存在的记录 → NOT_FOUND，不泄露存在性")
    void missingRecordFails() {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(ToolExecutionService.BeginStatus.ACQUIRED, null));
        when(recordMapper.selectById(1L, "demo")).thenReturn(null);

        DemoRecordService.CompleteOutcome outcome =
                service.complete("demo", "c1", "cfm_1", "complete_demo_record", 1L);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("NOT_FOUND");
        verify(executionService).fail("demo", "cfm_1", "NOT_FOUND", "记录不存在或无权操作");
    }

    @Test
    @DisplayName("同一幂等键正在执行 → IN_PROGRESS")
    void inProgressIsRejected() {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(ToolExecutionService.BeginStatus.IN_PROGRESS, null));

        DemoRecordService.CompleteOutcome outcome =
                service.complete("demo", "c1", "cfm_1", "complete_demo_record", 1L);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("IN_PROGRESS");
    }

    @Test
    @DisplayName("同一幂等键换了参数 → IDEMPOTENCY_CONFLICT")
    void argsConflictIsRejected() {
        when(executionService.begin(any())).thenReturn(
                new ToolExecutionService.BeginResult(ToolExecutionService.BeginStatus.CONFLICT, null));

        DemoRecordService.CompleteOutcome outcome =
                service.complete("demo", "c1", "cfm_1", "complete_demo_record", 1L);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("IDEMPOTENCY_CONFLICT");
    }
}
