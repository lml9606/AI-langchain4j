package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 工具参数 Schema 校验单测：必填、类型、枚举、边界、额外参数与组合约束。 */
class ToolInputValidatorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ToolDefinition definition(String schema) {
        return new ToolDefinition("test_tool", "测试工具", schema, ToolSideEffect.READ_ONLY, 1000);
    }

    @Test
    @DisplayName("required 来自 JSON Schema，缺失时拒绝")
    void rejectMissingRequired() throws Exception {
        ToolDefinition definition = definition("""
                {"type":"object","required":["entityId"],
                 "properties":{"entityId":{"type":"string"}}}
                """);

        ToolInputValidator.Result result = ToolInputValidator.validate(definition, json("{}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("entityId");
    }

    @Test
    @DisplayName("类型、枚举和数值边界")
    void rejectInvalidTypeEnumAndRange() throws Exception {
        ToolDefinition definition = definition("""
                {"type":"object","additionalProperties":false,
                 "properties":{
                   "status":{"type":"string","enum":["OPEN","DONE"]},
                   "limit":{"type":"integer","minimum":1,"maximum":20}}}
                """);

        assertThat(ToolInputValidator.validate(definition,
                json("{\"status\":\"BAD\"}")).valid()).isFalse();
        assertThat(ToolInputValidator.validate(definition,
                json("{\"limit\":21}")).valid()).isFalse();
        assertThat(ToolInputValidator.validate(definition,
                json("{\"limit\":\"5\"}")).valid()).isFalse();
        assertThat(ToolInputValidator.validate(definition,
                json("{\"status\":\"OPEN\",\"limit\":5}")).valid()).isTrue();
    }

    @Test
    @DisplayName("additionalProperties=false 拒绝模型注入的未知参数")
    void rejectUnknownArgument() throws Exception {
        ToolDefinition definition = definition("""
                {"type":"object","additionalProperties":false,
                 "properties":{"query":{"type":"string"}}}
                """);

        ToolInputValidator.Result result = ToolInputValidator.validate(definition,
                json("{\"query\":\"abc\",\"admin\":true}"));

        assertThat(result.valid()).isFalse();
        assertThat(result.message()).contains("admin");
    }

    @Test
    @DisplayName("anyOf 支持 recordId/title 二选一")
    void validateAnyOf() throws Exception {
        ToolDefinition definition = definition("""
                {"type":"object","additionalProperties":false,
                 "anyOf":[{"required":["recordId"]},{"required":["title"]}],
                 "properties":{
                   "recordId":{"type":"integer","minimum":1},
                   "title":{"type":"string","minLength":1}}}
                """);

        assertThat(ToolInputValidator.validate(definition, json("{}")).valid()).isFalse();
        assertThat(ToolInputValidator.validate(definition,
                json("{\"recordId\":1}")).valid()).isTrue();
        assertThat(ToolInputValidator.validate(definition,
                json("{\"title\":\"任务 A\"}")).valid()).isTrue();
    }

    private JsonNode json(String content) throws Exception {
        return MAPPER.readTree(content);
    }
}
