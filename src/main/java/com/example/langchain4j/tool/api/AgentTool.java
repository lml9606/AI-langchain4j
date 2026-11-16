package com.example.langchain4j.tool.api;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具契约：所有工具由 Spring 自动发现并注册到 ToolRegistry。
 */
public interface AgentTool {

    ToolDefinition definition();

    ToolResult execute(ToolContext context, JsonNode args) throws Exception;
}
