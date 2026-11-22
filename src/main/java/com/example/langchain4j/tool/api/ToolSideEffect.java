package com.example.langchain4j.tool.api;

/** 工具副作用级别：执行器据此决定是否需要人工确认。 */
public enum ToolSideEffect {
    READ_ONLY,
    WRITE,
    DANGEROUS
}
