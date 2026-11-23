package com.example.langchain4j.audit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 审计载荷压缩单测：保证节点审计不会重复存整段上下文。 */
class AuditPayloadsTest {

    @Test
    @DisplayName("输出摘要：字符串截断，集合只保留大小")
    void summarizesOutput() {
        Map<String, Object> output = AuditPayloads.output(Map.of(
                "result", "ok",
                "context", "x".repeat(100),
                "hits", List.of(1, 2, 3)), 10);

        assertThat(output).containsEntry("result", "ok");
        assertThat((String) output.get("context")).startsWith("xxxxxxxxxx...[truncated]");
        assertThat(output.get("hits")).isEqualTo(Map.of("size", 3));
    }

    @Test
    @DisplayName("返回值实现 AuditPayloadProvider 时优先使用结构化载荷")
    void usesAuditPayloadProvider() {
        AuditPayloadProvider result = () -> Map.of("topic", "JVM", "newTopic", false);

        assertThat(AuditPayloads.result(result, 100))
                .containsEntry("topic", "JVM")
                .containsEntry("newTopic", false);
    }

    @Test
    @DisplayName("方法入参：按参数名保留标量")
    void summarizesMethodArgs() {
        Map<String, Object> input = AuditPayloads.methodInput(
                new String[]{"conversationId", "query"}, new Object[]{"c1", "hello"}, 100);

        assertThat(input).containsEntry("conversationId", "c1")
                .containsEntry("query", "hello");
    }
}
