package com.example.langchain4j.service;

import com.example.langchain4j.repository.entity.ToolDemoRecord;
import com.example.langchain4j.repository.mapper.ToolDemoRecordMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 写工具业务单测：只验证业务状态幂等，台账行为由 WriteExecutionTemplateTest 覆盖。
 */
@ExtendWith(MockitoExtension.class)
class DemoRecordServiceTest {

    @Mock
    private ToolDemoRecordMapper recordMapper;

    private DemoRecordService service;

    @BeforeEach
    void setUp() {
        service = new DemoRecordService(recordMapper);
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
    @DisplayName("首次执行：更新业务状态并返回完成结果")
    void firstExecutionWritesBusinessState() {
        when(recordMapper.selectById(1L, "demo")).thenReturn(record(ToolDemoRecord.STATUS_OPEN));
        when(recordMapper.markDone(1L, "demo")).thenReturn(1);

        DemoRecordService.CompleteOutcome outcome = service.complete("demo", 1L);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.replayed()).isFalse();
        assertThat(outcome.result()).contains("标记为完成");
        verify(recordMapper).markDone(1L, "demo");
    }

    @Test
    @DisplayName("记录已经是 DONE：按业务幂等返回成功，不重复修改")
    void alreadyDoneIsIdempotentSuccess() {
        when(recordMapper.selectById(1L, "demo")).thenReturn(record(ToolDemoRecord.STATUS_DONE));

        DemoRecordService.CompleteOutcome outcome = service.complete("demo", 1L);

        assertThat(outcome.success()).isTrue();
        assertThat(outcome.replayed()).isTrue();
        assertThat(outcome.result()).contains("已经是完成状态");
        verify(recordMapper, never()).markDone(anyLong(), anyString());
    }

    @Test
    @DisplayName("越权/不存在的记录 → NOT_FOUND，不泄露存在性")
    void missingRecordFails() {
        when(recordMapper.selectById(1L, "demo")).thenReturn(null);

        DemoRecordService.CompleteOutcome outcome = service.complete("demo", 1L);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("NOT_FOUND");
    }

    @Test
    @DisplayName("更新失败且记录仍非 DONE → UPDATE_FAILED")
    void updateFailureIsReported() {
        when(recordMapper.selectById(1L, "demo")).thenReturn(record(ToolDemoRecord.STATUS_OPEN));
        when(recordMapper.markDone(1L, "demo")).thenReturn(0);
        when(recordMapper.selectById(1L, "demo")).thenReturn(record(ToolDemoRecord.STATUS_OPEN));

        DemoRecordService.CompleteOutcome outcome = service.complete("demo", 1L);

        assertThat(outcome.success()).isFalse();
        assertThat(outcome.errorCode()).isEqualTo("UPDATE_FAILED");
    }
}
