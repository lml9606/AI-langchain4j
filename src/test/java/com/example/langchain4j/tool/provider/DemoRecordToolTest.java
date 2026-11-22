package com.example.langchain4j.tool.provider;

import com.example.langchain4j.repository.entity.ToolDemoRecord;
import com.example.langchain4j.service.DemoRecordService;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 演示工具单测：用户隔离、空结果表达、参数定位。
 *
 * <p>确认、幂等键和台账由执行层统一负责，不在业务工具里重复校验；
 * 对应行为见 ToolExecutorTest 与 WriteExecutionTemplateTest。
 */
@ExtendWith(MockitoExtension.class)
class DemoRecordToolTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private DemoRecordService demoRecordService;

    @Test
    @DisplayName("读工具：无数据时明确返回没有查询到，不伪造结果")
    void queryReturnsEmptyMessage() {
        DemoRecordQueryTool tool = new DemoRecordQueryTool(demoRecordService);
        when(demoRecordService.list("demo", null, 5)).thenReturn(List.of());

        ToolResult result = tool.execute(new ToolContext("demo", "c1"), MAPPER.createObjectNode());

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("没有查询到");
    }

    @Test
    @DisplayName("读工具：缺少用户身份 → 拒绝执行")
    void queryRejectsAnonymous() {
        DemoRecordQueryTool tool = new DemoRecordQueryTool(demoRecordService);

        ToolResult result = tool.execute(new ToolContext("", "c1"), MAPPER.createObjectNode());

        assertThat(result.errorCode()).isEqualTo("UNAUTHORIZED_USER");
        verify(demoRecordService, never()).list(anyString(), anyString(), anyInt());
    }

    @Test
    @DisplayName("写工具：参数非法 → INVALID_ARGS")
    void completeRejectsInvalidRecordId() throws Exception {
        DemoRecordCompleteTool tool = new DemoRecordCompleteTool(demoRecordService);
        JsonNode args = MAPPER.readTree("{\"recordId\":0}");

        ToolResult result = tool.execute(new ToolContext("demo", "c1", true, "cfm_1"), args);

        assertThat(result.errorCode()).isEqualTo("INVALID_ARGS");
        verify(demoRecordService, never()).complete(anyString(), anyLong());
    }

    @Test
    @DisplayName("写工具：正常执行 → 返回完成结果")
    void completeSuccess() throws Exception {
        DemoRecordCompleteTool tool = new DemoRecordCompleteTool(demoRecordService);
        JsonNode args = MAPPER.readTree("{\"recordId\":1}");
        when(demoRecordService.complete("demo", 1L))
                .thenReturn(new DemoRecordService.CompleteOutcome(
                        true, false, "已将记录 1 标记为完成", "", ""));

        ToolResult result = tool.execute(new ToolContext("demo", "c1", true, "cfm_1"), args);

        assertThat(result.success()).isTrue();
        assertThat(result.summary()).contains("标记为完成");
        verify(demoRecordService).complete("demo", 1L);
    }

    @Test
    @DisplayName("写工具：按标题精确定位 → 解析为 recordId 后执行")
    void completeResolvesByTitle() throws Exception {
        DemoRecordCompleteTool tool = new DemoRecordCompleteTool(demoRecordService);
        JsonNode args = MAPPER.readTree("{\"title\":\"演示任务 A\"}");
        when(demoRecordService.findByTitle("demo", "演示任务 A")).thenReturn(List.of(record(1L)));
        when(demoRecordService.complete("demo", 1L))
                .thenReturn(new DemoRecordService.CompleteOutcome(
                        true, false, "已将记录 1 标记为完成", "", ""));

        ToolResult result = tool.execute(new ToolContext("demo", "c1", true, "cfm_1"), args);

        assertThat(result.success()).isTrue();
        verify(demoRecordService).complete("demo", 1L);
    }

    @Test
    @DisplayName("写工具：标题重名 → 拒绝执行，不猜一个改错数据")
    void ambiguousTitleRejected() throws Exception {
        DemoRecordCompleteTool tool = new DemoRecordCompleteTool(demoRecordService);
        JsonNode args = MAPPER.readTree("{\"title\":\"重名任务\"}");
        when(demoRecordService.findByTitle("demo", "重名任务")).thenReturn(List.of(record(1L), record(2L)));

        ToolResult result = tool.execute(new ToolContext("demo", "c1", true, "cfm_1"), args);

        assertThat(result.errorCode()).isEqualTo("INVALID_ARGS");
        verify(demoRecordService, never()).complete(anyString(), anyLong());
    }

    private ToolDemoRecord record(long id) {
        ToolDemoRecord record = new ToolDemoRecord();
        record.setId(id);
        record.setUserId("demo");
        record.setStatus(ToolDemoRecord.STATUS_OPEN);
        return record;
    }
}
