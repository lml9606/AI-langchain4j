package com.example.langchain4j.graph.task;

import com.example.langchain4j.tool.api.ToolDefinition;
import com.example.langchain4j.tool.api.ToolSideEffect;
import com.example.langchain4j.tool.runtime.ToolRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;

/**
 * ReAct 工具目录：只暴露受控只读诊断允许的工具。
 *
 * 这是 ReAct 的授权白名单，不是全部工具目录。即使模型输出其他已注册工具名，
 * 规划节点和执行节点都会按这里拒绝；写工具永远不会进入该目录。
 */
@Component
public class ReactToolCatalog {

    private static final String SEARCH_KNOWLEDGE = "search_knowledge";
    private static final Set<String> ALLOWED = Set.of(
            TaskTools.READ_ENROLLMENT,
            TaskTools.READ_QUOTA,
            TaskTools.READ_TRANSACTION,
            SEARCH_KNOWLEDGE);
    private static final Set<String> BUSINESS_DATA_TOOLS = Set.of(
            TaskTools.READ_ENROLLMENT,
            TaskTools.READ_QUOTA,
            TaskTools.READ_TRANSACTION);

    private final ToolRegistry registry;

    public ReactToolCatalog(ToolRegistry registry) {
        this.registry = registry;
    }

    /**
     * 返回可展示给模型的工具定义，顺序固定便于 prompt 稳定和测试复现。
     * 只返回注册表中存在、已启用且副作用为只读的工具。
     */
    public List<ToolDefinition> definitions() {
        List<ToolDefinition> definitions = new ArrayList<>(ALLOWED.size());
        for (String name : List.of(TaskTools.READ_ENROLLMENT, TaskTools.READ_QUOTA,
                TaskTools.READ_TRANSACTION, SEARCH_KNOWLEDGE)) {
            find(name).ifPresent(definitions::add);
        }
        return definitions;
    }

    /**
     * 按名称查找允许的只读工具；未在白名单、未注册、已停用或非只读都返回空。
     * 执行前再查一次，保证运行期策略变化不会被旧计划绕过。
     */
    public Optional<ToolDefinition> find(String name) {
        if (name == null || !ALLOWED.contains(name)) {
            return Optional.empty();
        }
        return registry.definition(name)
                .filter(definition -> definition.sideEffect() == ToolSideEffect.READ_ONLY);
    }

    /** 判断工具是否在 ReAct 白名单中且为只读。 */
    public boolean allows(String name) {
        return find(name).isPresent();
    }

    /** 该工具的查询结果是否应进入 businessData；知识检索只进入 citations/evidence。 */
    public static boolean contributesBusinessData(String name) {
        return BUSINESS_DATA_TOOLS.contains(name);
    }

    /**
     * 生成稳定的调用签名，用于识别“同工具同参数”的重复调用。
     *
     * 对象字段按名称排序，数组保持原顺序；参数顺序变化不会绕过重复调用保护。
     */
    public static String signature(String tool, JsonNode args) {
        StringBuilder builder = new StringBuilder(tool == null ? "" : tool).append('|');
        appendCanonical(args, builder);
        return builder.toString();
    }

    private static void appendCanonical(JsonNode node, StringBuilder builder) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            builder.append("null");
            return;
        }
        if (node.isObject()) {
            Map<String, JsonNode> fields = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> iterator = node.fields();
            while (iterator.hasNext()) {
                Map.Entry<String, JsonNode> entry = iterator.next();
                fields.put(entry.getKey(), entry.getValue());
            }
            builder.append('{');
            boolean first = true;
            for (Map.Entry<String, JsonNode> entry : fields.entrySet()) {
                if (!first) {
                    builder.append(',');
                }
                first = false;
                builder.append(entry.getKey()).append(':');
                appendCanonical(entry.getValue(), builder);
            }
            builder.append('}');
            return;
        }
        if (node.isArray()) {
            builder.append('[');
            for (int i = 0; i < node.size(); i++) {
                if (i > 0) {
                    builder.append(',');
                }
                appendCanonical(node.get(i), builder);
            }
            builder.append(']');
            return;
        }
        builder.append(node);
    }
}
