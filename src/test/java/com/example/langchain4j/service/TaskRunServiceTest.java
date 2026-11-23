package com.example.langchain4j.service;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.repository.mapper.TaskRunMapper;
import com.example.langchain4j.repository.mapper.TaskStepMapper;
import com.fasterxml.jackson.core.type.TypeReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 任务状态机：取消和重试必须同时维护 run 与 step 两层状态。 */
@ExtendWith(MockitoExtension.class)
class TaskRunServiceTest {

    @Mock
    private TaskRunMapper runMapper;
    @Mock
    private TaskStepMapper stepMapper;

    private TaskRunService service;

    @BeforeEach
    void setUp() {
        service = new TaskRunService(runMapper, stepMapper);
    }

    @Test
    @DisplayName("新消息取消旧任务 -> run 和步骤一起取消")
    void cancelByConversationAlsoCancelsSteps() {
        when(runMapper.markCancelledByConversation("demo", "c1")).thenReturn(2);

        service.cancelByConversation("demo", "c1");

        verify(stepMapper).markCancelledByConversation("demo", "c1");
    }

    @Test
    @DisplayName("没有可取消任务 -> 不改步骤状态")
    void cancelByConversationSkipsStepsWhenNoRunChanged() {
        when(runMapper.markCancelledByConversation("demo", "c1")).thenReturn(0);

        service.cancelByConversation("demo", "c1");

        verify(stepMapper, never()).markCancelledByConversation("demo", "c1");
    }

    @Test
    @DisplayName("用户取消确认 -> 只取消待确认 run，并同步步骤")
    void cancelSingleRun() {
        when(runMapper.markCancelled("demo", "run_1")).thenReturn(1);

        assertThat(service.cancel("demo", "run_1")).isTrue();

        verify(stepMapper).markCancelledByRun("run_1");
    }

    @Test
    @DisplayName("run 已进入执行/终态 -> 不取消步骤")
    void cancelSingleRunDoesNothingWhenStatusChanged() {
        when(runMapper.markCancelled("demo", "run_1")).thenReturn(0);

        assertThat(service.cancel("demo", "run_1")).isFalse();

        verify(stepMapper, never()).markCancelledByRun("run_1");
    }

    @Test
    @DisplayName("失败重试 -> 恢复被跳过的步骤")
    void resetSkippedForRetry() {
        service.resetSkipped("run_1");

        verify(stepMapper).resetSkipped("run_1");
    }

    @Test
    @DisplayName("续租失败 -> 返回 false，调用方停止推进")
    void renewLeaseReturnsOwnerResult() {
        when(runMapper.renewLease("run_1", "owner", 9)).thenReturn(0);

        assertThat(service.renewLease("run_1", "owner", 9)).isFalse();
    }

    @Test
    @DisplayName("计划指纹经过 Redis JSON 往返后保持一致")
    void planHashStableAfterJsonRoundTrip() {
        Map<String, Object> args = new LinkedHashMap<>();
        args.put("recordId", 7);
        args.put("note", "确认后执行");
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("stepNo", 0);
        step.put("tool", "write_tool");
        step.put("args", args);
        List<Map<String, Object>> plan = List.of(step);
        String json = JsonUtil.toJson(plan);
        List<Map<String, Object>> parsed = JsonUtil.fromJson(json,
                new TypeReference<List<Map<String, Object>>>() {
                });

        assertThat(service.planHash(JsonUtil.toJson(parsed)))
                .isEqualTo(service.planHash(json));
    }
}
