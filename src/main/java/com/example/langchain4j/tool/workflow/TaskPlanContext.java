package com.example.langchain4j.tool.workflow;

import com.example.langchain4j.tool.api.ToolDefinition;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提供给扩展规则的只读上下文。
 *
 * 扩展规则可以看到阶段、工作流、完整计划、工具定义和待确认动作，但规则本身只负责返回
 * “是否拒绝”，不能修改计划。真正的计划变更必须回到规划节点重新生成并重新走内置安全校验。
 */
public record TaskPlanContext(TaskPlanPhase phase,
                              TaskWorkflow workflow,
                              List<Map<String, Object>> plan,
                              Map<String, ToolDefinition> definitions,
                              Map<String, Object> pendingAction) {

    public TaskPlanContext {
        phase = phase == null ? TaskPlanPhase.EXECUTE : phase;
        plan = snapshotPlan(plan);
        definitions = definitions == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(definitions));
        pendingAction = snapshotMap(pendingAction);
    }

    /**
     * 深拷贝计划中的 Map/List，并全部包成不可修改视图。
     *
     * 扩展规则拿到的是快照而不是状态里的原对象，即使规则内部尝试修改也不会影响真正执行的计划；
     * 误修改会直接得到 UnsupportedOperationException，由校验器 fail-closed。
     */
    private static List<Map<String, Object>> snapshotPlan(List<Map<String, Object>> plan) {
        if (plan == null || plan.isEmpty()) {
            return List.of();
        }
        List<Map<String, Object>> snapshot = new ArrayList<>(plan.size());
        for (Map<String, Object> step : plan) {
            snapshot.add(step == null ? null : snapshotMap(step));
        }
        return Collections.unmodifiableList(snapshot);
    }

    private static Map<String, Object> snapshotMap(Map<String, Object> source) {
        if (source == null || source.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        source.forEach((key, value) -> snapshot.put(key, snapshotValue(value)));
        return Collections.unmodifiableMap(snapshot);
    }

    private static Object snapshotValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> snapshot = new LinkedHashMap<>();
            map.forEach((key, item) ->
                    snapshot.put(String.valueOf(key), snapshotValue(item)));
            return Collections.unmodifiableMap(snapshot);
        }
        if (value instanceof List<?> list) {
            List<Object> snapshot = new ArrayList<>(list.size());
            for (Object item : list) {
                snapshot.add(snapshotValue(item));
            }
            return Collections.unmodifiableList(snapshot);
        }
        return value;
    }
}
