package com.example.langchain4j.tool.api.contract;

import com.example.langchain4j.tool.api.model.ToolContext;
import com.example.langchain4j.tool.api.model.ToolDefinition;
import com.example.langchain4j.tool.api.model.ToolResult;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 工具契约：所有工具由 Spring 自动发现并注册到 ToolRegistry。
 */
public interface AgentTool {

    /** 返回工具说明书；名称、Schema、副作用和策略都以该定义为准。 */
    ToolDefinition definition();

    /** 执行一次工具调用；参数已由执行器做 Schema 校验，异常由执行器统一转失败结果。 */
    ToolResult execute(ToolContext context, JsonNode args) throws Exception;
}
