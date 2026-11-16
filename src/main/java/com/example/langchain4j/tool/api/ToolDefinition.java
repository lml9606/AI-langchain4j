package com.example.langchain4j.tool.api;

import java.util.List;

/**
 * 工具说明书：名称、描述、参数 Schema、必填参数、副作用和超时。
 */
public record ToolDefinition(String name,
                             String description,
                             String inputSchema,
                             List<String> requiredArgs,
                             ToolSideEffect sideEffect,
                             int timeoutMs) {

    public ToolDefinition {
        name = name == null ? "" : name;
        description = description == null ? "" : description;
        inputSchema = inputSchema == null ? "{\"type\":\"object\",\"properties\":{}}" : inputSchema;
        requiredArgs = requiredArgs == null ? List.of() : List.copyOf(requiredArgs);
        sideEffect = sideEffect == null ? ToolSideEffect.READ_ONLY : sideEffect;
    }
}
