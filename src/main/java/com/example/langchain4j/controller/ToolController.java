package com.example.langchain4j.controller;

import com.example.langchain4j.common.auth.RequireRole;
import com.example.langchain4j.common.auth.UserContext;
import com.example.langchain4j.common.auth.UserRole;
import com.example.langchain4j.common.response.ApiResponse;
import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.tool.api.ToolContext;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolResult;
import com.example.langchain4j.tool.runtime.ToolExecutor;
import com.example.langchain4j.tool.runtime.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 工具管理接口：查看已注册工具与手工调试调用。
 * <p>
 * 仅管理员可用；手工调用同样经过 ToolExecutor 的只读校验、参数校验、超时、限流与审计，
 * 不绕过任何线上防护。生产环境可关闭该接口或限制内网访问。
 */
@RestController
@RequestMapping("/api/tools")
@RequireRole(UserRole.ADMIN)
public class ToolController {

    private final ToolRegistry toolRegistry;
    private final ToolExecutor toolExecutor;

    public ToolController(ToolRegistry toolRegistry, ToolExecutor toolExecutor) {
        this.toolRegistry = toolRegistry;
        this.toolExecutor = toolExecutor;
    }

    /** 列出已注册工具（名称/描述/参数 Schema/副作用/超时） */
    @GetMapping
    public ApiResponse<List<ToolDefinition>> list() {
        return ApiResponse.success(toolRegistry.definitions());
    }

    /**
     * 手工调用工具（调试用）：参数为 JSON 对象，执行结果结构同图内调用。
     *
     * @param name 工具名（来自 GET /api/tools）
     * @param args 工具参数
     * @return 结构化 ToolResult（success/data/summary/evidence/costMs）
     */
    @PostMapping("/{name}/invoke")
    public ApiResponse<ToolResult> invoke(@PathVariable String name,
                                          @RequestBody(required = false) Map<String, Object> args) {
        JsonNode json = JsonUtil.mapper().valueToTree(args == null ? Map.of() : args);
        ToolContext context = new ToolContext(UserContext.require(), "tool-debug");
        return ApiResponse.success(toolExecutor.invoke(context, name, json));
    }
}
