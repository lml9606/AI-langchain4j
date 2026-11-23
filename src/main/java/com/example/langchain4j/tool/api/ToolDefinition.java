package com.example.langchain4j.tool.api;

import java.util.Set;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * 工具说明书：名称、描述、参数 Schema、副作用、超时与策略。
 *
 * 工具的定义和策略以本对象为唯一事实源，不再在 application.properties 和
 * 数据库里重复维护工具白名单/写权限。配置只保留全局应急开关和默认运行预算。
 */
public record ToolDefinition(String name,
                             String description,
                             String inputSchema,
                             ToolSideEffect sideEffect,
                             int timeoutMs,
                             ToolPolicy policy) {

    public ToolDefinition {
        name = name == null ? "" : name.trim();
        description = description == null ? "" : description.trim();
        inputSchema = inputSchema == null || inputSchema.isBlank()
                ? "{\"type\":\"object\",\"properties\":{}}" : inputSchema.trim();
        sideEffect = sideEffect == null ? ToolSideEffect.READ_ONLY : sideEffect;
        policy = policy == null ? ToolPolicy.defaults() : policy;
    }

    /** 最小定义：使用默认策略。 */
    public ToolDefinition(String name, String description, String inputSchema,
                          ToolSideEffect sideEffect, int timeoutMs) {
        this(name, description, inputSchema, sideEffect, timeoutMs, ToolPolicy.defaults());
    }

    /**
     * 工具策略：是否启用、单工具限流覆盖值、允许角色。
     *
     * maxCallsPerMinute=0 表示使用全局默认值；allowedRoles 为空表示不做角色限制。
     */
    public record ToolPolicy(boolean enabled, int maxCallsPerMinute, Set<String> allowedRoles) {

        public ToolPolicy {
            maxCallsPerMinute = Math.max(0, maxCallsPerMinute);
            allowedRoles = allowedRoles == null ? Set.of() : allowedRoles.stream()
                    .filter(role -> role != null && !role.isBlank())
                    .map(String::trim)
                    .map(role -> role.toUpperCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        }

        public static ToolPolicy defaults() {
            return new ToolPolicy(true, 0, Set.of());
        }

        public boolean allowsRole(String role) {
            if (allowedRoles.isEmpty()) {
                return true;
            }
            String normalized = role == null ? "" : role.trim().toUpperCase(Locale.ROOT);
            return !normalized.isEmpty() && allowedRoles.contains(normalized);
        }
    }
}
