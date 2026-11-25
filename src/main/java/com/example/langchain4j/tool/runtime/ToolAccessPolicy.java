package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.tool.api.model.ToolDefinition;
import com.example.langchain4j.tool.api.model.ToolSideEffect;
import org.springframework.stereotype.Component;

/**
 * 工具访问策略：在规划入口、执行节点和最终执行器之间复用同一套权限语义。
 *
 * 返回 {@code PERMISSION_REQUIRED} 表示“能力存在但当前用户无权使用”，
 * 不会把一个无权限工具静默过滤后再让模型从剩余工具中随意选择。
 */
@Component
public class ToolAccessPolicy {

    private final ToolRegistry toolRegistry;
    private final AgentProperties properties;

    public ToolAccessPolicy(ToolRegistry toolRegistry, AgentProperties properties) {
        this.toolRegistry = toolRegistry;
        this.properties = properties;
    }

    /**
     * 按工具名校验：内部先查注册表，再走统一的定义校验。
     *
     * 适用于只有工具名、没有定义对象的场景，例如执行计划里的步骤。
     */
    public Decision check(String toolName, String role) {
        return check(toolRegistry.definition(toolName).orElse(null), toolName, role);
    }

    /**
     * 按已解析的工具定义校验：执行器和确认节点已经持有定义时走这个重载，
     * 避免同一次调用重复查注册表，也保证校验使用的定义与执行使用的定义是同一份。
     */
    public Decision check(ToolDefinition definition, String role) {
        return check(definition, definition == null ? "" : definition.name(), role);
    }

    private Decision check(ToolDefinition definition, String toolName, String role) {
        if (definition == null || !definition.policy().enabled()) {
            return new Decision(false, Status.NOT_FOUND, toolName,
                    "当前任务所需的工具不存在或已停用，请联系管理员");
        }
        if (definition.sideEffect() == ToolSideEffect.DANGEROUS) {
            return new Decision(false, Status.DANGEROUS, toolName,
                    "任务包含禁止自动执行的危险工具");
        }
        if (!definition.policy().allowsRole(role)) {
            return new Decision(false, Status.PERMISSION_REQUIRED, toolName,
                    "当前角色无权执行该任务");
        }
        if (definition.sideEffect() == ToolSideEffect.WRITE
                && !properties.getTool().isWriteEnabled()) {
            return new Decision(false, Status.WRITE_DISABLED, toolName,
                    "写操作在当前环境已禁用");
        }
        return new Decision(true, Status.ALLOWED, toolName, "");
    }

    public enum Status {
        ALLOWED,
        NOT_FOUND,
        PERMISSION_REQUIRED,
        WRITE_DISABLED,
        DANGEROUS
    }

    /** 策略结论：用户可读消息不泄露具体权限配置。 */
    public record Decision(boolean allowed, Status status, String toolName, String message) {
    }
}
