package com.example.langchain4j.tool.workflow;

import java.util.ArrayList;
import java.util.List;

/**
 * 任务工作流契约：声明一次业务任务需要哪些工具、按什么阶段和顺序执行。
 *
 * <p>工具集合由代码契约固定，模型只负责填写参数，不能新增、删除、替换或重排工具。
 * 这样工具选择不再依赖固定 TopK 召回，也不会因为模型误选而越权或漏调。
 */
public interface TaskWorkflow {

    /** 稳定工作流 ID，供模型选择与执行期复核。 */
    String id();

    /** 面向模型的能力说明，不包含工具内部实现细节。 */
    String description();

    /** 按执行顺序声明的阶段；同一阶段内的工具按声明顺序执行。 */
    List<Stage> stages();

    /** 展平后的固定工具序列。 */
    default List<String> requiredTools() {
        List<String> names = new ArrayList<>();
        for (Stage stage : stages()) {
            names.addAll(stage.tools());
        }
        return List.copyOf(names);
    }

    /** 执行阶段：阶段 ID 用于追踪，tools 是该阶段允许调用的完整工具集合。 */
    record Stage(String id, String description, List<String> tools) {

        public Stage {
            id = id == null ? "" : id.trim();
            description = description == null ? "" : description.trim();
            tools = tools == null ? List.of() : tools.stream()
                    .filter(tool -> tool != null && !tool.isBlank())
                    .map(String::trim)
                    .toList();
        }
    }
}
