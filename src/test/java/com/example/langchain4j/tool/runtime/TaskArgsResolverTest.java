package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.repository.entity.TaskStep;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 步骤参数引用解析：写步骤可以使用前面只读步骤的真实结果。 */
class TaskArgsResolverTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final TaskArgsResolver resolver = new TaskArgsResolver();

    @Test
    @DisplayName("点路径引用 -> 取到查询结果里的 recordId")
    void resolveDotPath() throws Exception {
        TaskStep read = step(0, TaskStep.Status.SUCCEEDED, "[{\"id\":7,\"title\":\"A\"}]");
        JsonNode args = mapper.readTree(
                "{\"recordId\":{\"$ref\":\"0.0.id\"},\"note\":\"$ref:0.0.title\"}");

        JsonNode resolved = resolver.resolve(args, Map.of(0, read));

        assertThat(resolved.get("recordId").asInt()).isEqualTo(7);
        assertThat(resolved.get("note").asText()).isEqualTo("A");
    }

    @Test
    @DisplayName("JSON Pointer 引用 -> 与点路径等价")
    void resolveJsonPointer() throws Exception {
        TaskStep read = step(1, TaskStep.Status.SUCCEEDED, "{\"rows\":[{\"id\":9}]}");
        JsonNode args = mapper.readTree("{\"recordId\":{\"$ref\":\"1#/rows/0/id\"}}");

        JsonNode resolved = resolver.resolve(args, Map.of(1, read));

        assertThat(resolved.get("recordId").asInt()).isEqualTo(9);
    }

    @Test
    @DisplayName("引用未成功步骤 -> 明确失败，不把占位符发给工具")
    void rejectUnfinishedStep() throws Exception {
        TaskStep read = step(0, TaskStep.Status.FAILED, null);
        JsonNode args = mapper.readTree("{\"recordId\":{\"$ref\":\"0.0.id\"}}");

        assertThatThrownBy(() -> resolver.resolve(args, Map.of(0, read)))
                .isInstanceOf(TaskArgsResolver.TaskRefException.class)
                .hasMessageContaining("尚未成功");
    }

    @Test
    @DisplayName("引用不存在的路径 -> 明确失败")
    void rejectMissingPath() throws Exception {
        TaskStep read = step(0, TaskStep.Status.SUCCEEDED, "[{\"id\":7}]");
        JsonNode args = mapper.readTree("{\"recordId\":{\"$ref\":\"0.0.missing\"}}");

        assertThatThrownBy(() -> resolver.resolve(args, Map.of(0, read)))
                .isInstanceOf(TaskArgsResolver.TaskRefException.class)
                .hasMessageContaining("路径不存在");
    }

    private TaskStep step(int no, TaskStep.Status status, String resultJson) {
        TaskStep step = new TaskStep();
        step.setStepNo(no);
        step.setStatus(status);
        step.setResultJson(resultJson);
        return step;
    }
}
