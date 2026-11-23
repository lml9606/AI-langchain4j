package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.util.Texts;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import org.springframework.stereotype.Component;

/**
 * 确认资格策略：判断一个工具是否允许进入人工确认流程。
 *
 * 这里只回答“能不能确认”，不负责生成令牌、执行工具或比较完整计划。把判断从节点中独立出来，
 * 后续新增审批规则时可以复用同一结论，而不需要复制节点里的 if/else。
 */
@Component
public class ConfirmationPolicy {

    private final ToolRegistry toolRegistry;
    private final ToolAccessPolicy accessPolicy;
    private final AgentProperties properties;

    public ConfirmationPolicy(ToolRegistry toolRegistry, ToolAccessPolicy accessPolicy,
                              AgentProperties properties) {
        this.toolRegistry = toolRegistry;
        this.accessPolicy = accessPolicy;
        this.properties = properties;
    }

    /**
     * 校验待确认工具本身。
     *
     * 顺序保持为：参数是否完整、全局写开关、工具是否存在、是否只读、角色和危险工具权限。
     * 这样错误提示稳定，也避免先查注册表再发现配置关闭。
     */
    public Decision check(String toolName, String role) {
        String normalizedName = Texts.trimmedTextOr(toolName, "");
        if (normalizedName.isBlank()) {
            return new Decision(false, null, Status.MISSING_TOOL,
                    "确认请求缺少待执行工具");
        }
        if (!properties.getTool().isWriteEnabled()) {
            return new Decision(false, null, Status.WRITE_DISABLED,
                    "写工具在当前环境已禁用，不能创建确认请求");
        }
        ToolRegistry.RegisteredTool registered = toolRegistry.findRegistered(normalizedName)
                .orElse(null);
        if (registered == null) {
            return new Decision(false, null, Status.UNKNOWN_TOOL,
                    "待执行工具不存在或已停用: " + normalizedName);
        }
        ToolDefinition definition = registered.definition();
        if (definition.sideEffect() == ToolSideEffect.READ_ONLY) {
            return new Decision(false, definition, Status.NOT_WRITABLE,
                    "只读工具不需要人工确认: " + normalizedName);
        }

        ToolAccessPolicy.Decision access = accessPolicy.check(definition, role);
        if (!access.allowed()) {
            Status status = switch (access.status()) {
                case NOT_FOUND -> Status.UNKNOWN_TOOL;
                case WRITE_DISABLED -> Status.WRITE_DISABLED;
                default -> Status.FORBIDDEN_TOOL;
            };
            return new Decision(false, definition, status, access.message());
        }
        return new Decision(true, definition, Status.ALLOWED, "");
    }

    public enum Status {
        ALLOWED,
        MISSING_TOOL,
        UNKNOWN_TOOL,
        NOT_WRITABLE,
        WRITE_DISABLED,
        FORBIDDEN_TOOL
    }

    /** 确认资格结论；definition 只在工具已找到时返回，供生成确认卡片复用。 */
    public record Decision(boolean allowed, ToolDefinition definition,
                           Status status, String message) {
    }
}
