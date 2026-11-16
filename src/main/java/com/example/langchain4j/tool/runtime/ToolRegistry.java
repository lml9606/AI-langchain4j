package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolDefinition;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 工具注册表：启动期收集所有 AgentTool，重名直接 fail-fast。
 */
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;

    public ToolRegistry(List<AgentTool> discovered) {
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
        this.tools = Map.copyOf(map);
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
