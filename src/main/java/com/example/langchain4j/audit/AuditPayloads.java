package com.example.langchain4j.audit;

import com.example.langchain4j.graph.state.WorkflowState;

import java.lang.reflect.Array;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 审计载荷压缩工具：节点输出可能包含完整上下文、消息列表和工具轨迹，
 * 直接落库会造成审计表膨胀，这里统一做长度与结构摘要。
 */
public final class AuditPayloads {

    private static final int MAX_MAP_KEYS = 12;

    private AuditPayloads() {
    }

    /** 节点输入摘要：只保留排查必需的标量，不重复存整段上下文。 */
    public static Map<String, Object> nodeInput(WorkflowState state, int maxLength) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (state == null) {
            return input;
        }
        input.put("query", truncate(state.effectiveQuery(), maxLength));
        input.put("intent", state.intent());
        input.put("retrievalDepth", state.retrievalDepth());
        input.put("retryCount", state.retryCount());
        input.put("hopCount", state.hop().count());
        input.put("contextLength", state.context() == null ? 0 : state.context().length());
        input.put("planSteps", state.taskPlan().size());
        input.put("approved", state.taskApproved());
        return input;
    }

    /** 节点/方法输出摘要：字符串截断，集合和 Map 只保留大小与有限 key。 */
    public static Map<String, Object> output(Map<String, Object> source, int maxLength) {
        Map<String, Object> output = new LinkedHashMap<>();
        if (source == null) {
            return output;
        }
        source.forEach((key, value) -> output.put(key, summarize(value, maxLength)));
        return output;
    }

    /** 方法入参摘要：按参数名保留标量，复杂对象只记录结构。 */
    public static Map<String, Object> methodInput(String[] names, Object[] args, int maxLength) {
        Map<String, Object> input = new LinkedHashMap<>();
        if (args == null) {
            return input;
        }
        for (int index = 0; index < args.length; index++) {
            String name = names != null && index < names.length && names[index] != null
                    ? names[index] : "arg" + index;
            Object value = args[index];
            if (value instanceof WorkflowState state) {
                input.putAll(nodeInput(state, maxLength));
            } else {
                input.put(name, summarize(value, maxLength));
            }
        }
        return input;
    }

    /** 方法返回值摘要：支持结构化载荷提供者，否则按通用规则压缩。 */
    public static Map<String, Object> result(Object result, int maxLength) {
        if (result == null) {
            return Map.of();
        }
        if (result instanceof AuditPayloadProvider provider) {
            return output(provider.auditPayload(), maxLength);
        }
        if (result instanceof WorkflowState state) {
            Map<String, Object> output = new LinkedHashMap<>();
            output.put("intent", state.intent());
            output.put("fallback", state.fallback());
            output.put("done", state.done());
            output.put("rootCause", state.rootCause());
            output.put("resultLength", state.result() == null ? 0 : state.result().length());
            return output;
        }
        if (result instanceof Map<?, ?> map) {
            Map<String, Object> output = new LinkedHashMap<>();
            map.forEach((key, value) -> output.put(String.valueOf(key),
                    summarize(value, maxLength)));
            return output;
        }
        return Map.of("result", summarize(result, maxLength));
    }

    /** 通用摘要：标量保留、字符串截断、容器只保留结构，避免审计表无限膨胀。 */
    public static Object summarize(Object value, int maxLength) {
        if (value == null) {
            return null;
        }
        if (value instanceof CharSequence text) {
            return truncate(text.toString(), maxLength);
        }
        if (value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name();
        }
        if (value instanceof Collection<?> collection) {
            return Map.of("size", collection.size());
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("size", map.size());
            summary.put("keys", map.keySet().stream().limit(MAX_MAP_KEYS)
                    .map(String::valueOf).toList());
            return summary;
        }
        if (value.getClass().isArray()) {
            return Map.of("size", Array.getLength(value));
        }
        return truncate(String.valueOf(value), maxLength);
    }

    /** 超长截断：保留头部并明确标记，便于区分“原值就这么长”和“被审计截断”。 */
    public static String truncate(String value, int maxLength) {
        if (value == null) {
            return null;
        }
        int safeMax = Math.max(1, maxLength);
        return value.length() <= safeMax ? value : value.substring(0, safeMax) + "...[truncated]";
    }
}
