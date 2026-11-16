package com.example.langchain4j.tool.api;

/**
 * 工具副作用级别：本版本只允许只读工具。
 */
public enum ToolSideEffect {
    READ_ONLY,
    WRITE,
    DANGEROUS
}
