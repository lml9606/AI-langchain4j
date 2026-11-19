package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.config.AgentProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：启动期收集所有 AgentTool，重名直接 fail-fast。
 */
@Component
@Slf4j
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> discovered, AgentProperties properties) {
        Map<String, AgentTool> map = new LinkedHashMap<>();
        for (AgentTool tool : discovered) {
            ToolDefinition definition = tool.definition();
            if (definition == null || definition.name().isBlank()) {
                throw new IllegalStateException("[tool] tool definition/name must not be blank");
            }
            if (map.putIfAbsent(definition.name(), tool) != null) {
                throw new IllegalStateException("[tool] duplicate tool name: " + definition.name());
            }
        }
        // 白名单：为空表示不限制；非空时未列出的工具既不给模型看，也不能被执行
        List<String> enabled = properties.getTool().getEnabledTools();
        if (enabled != null && !enabled.isEmpty()) {
            List<String> missing = enabled.stream().filter(name -> !map.containsKey(name)).toList();
            if (!missing.isEmpty()) {
                throw new IllegalStateException("[tool] enabled tools not found: " + missing);
            }
            map.keySet().retainAll(new LinkedHashSet<>(enabled));
        }
        this.tools = Map.copyOf(map);
        log.info("[tool] enabled tools: {}", this.tools.keySet());
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(AgentTool::definition).toList();
    }

    public Optional<AgentTool> find(String name) {
        return Optional.ofNullable(tools.get(name));
    }

    public int size() {
        return tools.size();
    }
}
