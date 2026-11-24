package com.example.langchain4j.tool.runtime;

import com.example.langchain4j.common.util.JsonUtil;
import com.example.langchain4j.tool.api.AgentTool;
import com.example.langchain4j.tool.api.ToolDefinition;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * 工具注册表：启动期自动发现 AgentTool，校验定义并构建模型可见的工具目录。
 *
 * 单一事实源：工具的名称、Schema、副作用、超时、限流和角色都由
 * {@link ToolDefinition} 声明；配置文件不再维护工具白名单，数据库只存执行事实。
 *
 * 任务节点不再从注册表按相关度猜工具：固定读写节点按操作类型取唯一工具，
 * 注册表只负责定义校验与执行期查找。
 */
@Component
@Slf4j
public class ToolRegistry {

    private static final Pattern NAME_PATTERN = Pattern.compile("[a-z][a-z0-9_]{0,63}");
    private static final int MAX_TIMEOUT_MS = 120_000;

    private final Map<String, RegisteredTool> tools;

    public ToolRegistry(List<AgentTool> discovered) {
        Map<String, RegisteredTool> map = new LinkedHashMap<>();
        for (AgentTool tool : discovered) {
            if (tool == null) {
                throw new IllegalStateException("[tool] AgentTool bean must not be null");
            }
            ToolDefinition definition = tool.definition();
            validate(definition);
            if (!definition.policy().enabled()) {
                log.info("[tool] disabled by definition, name={}", definition.name());
                continue;
            }
            if (map.putIfAbsent(definition.name(), new RegisteredTool(tool, definition)) != null) {
                throw new IllegalStateException("[tool] duplicate tool name: " + definition.name());
            }
        }
        this.tools = Collections.unmodifiableMap(map);
        log.info("[tool] registered tools: {}", this.tools.keySet());
    }

    public List<ToolDefinition> definitions() {
        return tools.values().stream().map(RegisteredTool::definition).toList();
    }

    public Optional<AgentTool> find(String name) {
        RegisteredTool registered = tools.get(name);
        if (registered == null) {
            return Optional.empty();
        }
        return registered.definition().policy().enabled()
                ? Optional.of(registered.tool()) : Optional.empty();
    }

    /** 返回启动期固化的工具定义，避免执行期再次调用 AgentTool.definition() 造成策略漂移。 */
    public Optional<ToolDefinition> definition(String name) {
        RegisteredTool registered = tools.get(name);
        return registered == null ? Optional.empty() : Optional.of(registered.definition());
    }

    /** 一次查找同时拿到实现和稳定定义；执行器用这个入口。 */
    public Optional<RegisteredTool> findRegistered(String name) {
        RegisteredTool registered = tools.get(name);
        return registered != null && registered.definition().policy().enabled()
                ? Optional.of(registered) : Optional.empty();
    }

    public int size() {
        return tools.size();
    }

    /** 启动期校验：错误定义直接 fail-fast，避免运行时才发现工具不可用。 */
    private void validate(ToolDefinition definition) {
        if (definition == null || definition.name().isBlank()) {
            throw new IllegalStateException("[tool] tool definition/name must not be blank");
        }
        if (!NAME_PATTERN.matcher(definition.name()).matches()) {
            throw new IllegalStateException("[tool] invalid tool name (expected lower_snake_case): "
                    + definition.name());
        }
        if (definition.description().isBlank()) {
            throw new IllegalStateException("[tool] description must not be blank: " + definition.name());
        }
        if (definition.timeoutMs() < 0 || definition.timeoutMs() > MAX_TIMEOUT_MS) {
            throw new IllegalStateException("[tool] timeout must be between 0 and "
                    + MAX_TIMEOUT_MS + "ms: " + definition.name());
        }
        try {
            JsonNode schema = JsonUtil.mapper().readTree(definition.inputSchema());
            if (schema == null || !schema.isObject()) {
                throw new IllegalStateException("[tool] inputSchema must be a JSON object: "
                        + definition.name());
            }
            JsonNode type = schema.get("type");
            if (type != null && !"object".equals(type.asText())) {
                throw new IllegalStateException("[tool] root inputSchema type must be object: "
                        + definition.name());
            }
            JsonNode properties = schema.get("properties");
            if (properties != null && !properties.isObject()) {
                throw new IllegalStateException("[tool] inputSchema.properties must be object: "
                        + definition.name());
            }
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("[tool] invalid inputSchema JSON: "
                    + definition.name(), e);
        }
    }

    /** 注册项：实现与定义在启动期一次性绑定，防止运行中策略被实现方临时改写。 */
    public record RegisteredTool(AgentTool tool, ToolDefinition definition) {
    }
}
