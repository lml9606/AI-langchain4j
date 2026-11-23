package com.example.langchain4j.graph.state;

import com.example.langchain4j.common.util.JsonUtil;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.springframework.stereotype.Component;

/**
 * 工作流状态序列化器：Jackson 实现，用于 Checkpoint 持久化（PostgresSaver / MemorySaver）。
 * 使用独立 ObjectMapper 副本，避免污染全局 JsonUtil 实例。
 *
 * 嵌套子对象（Validation/Hop record）在序列化链路上的形态：序列化时 record 由 Jackson
 * 自动展开为 JSON 对象，与 Map 等价，落库无碍；反序列化时框架按 Map 还原，嵌套 JSON 对象
 * 变为 LinkedHashMap，由 {@link WorkflowState#validation()} / {@link WorkflowState#hop()}
 * 访问器防御转换回 record。旧版平铺字段（validated/hopCount 等）的升级迁移在
 * {@link WorkflowState} 构造函数完成，本类无需也无法介入，框架 readData/writeData 均为 final。
 */
@Component
public class WorkflowStateSerializer extends JacksonStateSerializer<WorkflowState> {

    public WorkflowStateSerializer() {
        // 参数1：状态工厂（反序列化时用 Map 重建 WorkflowState，旧平铺字段迁移在构造函数内完成）；
        // 参数2：JsonUtil 的独立 ObjectMapper 副本，避免污染全局实例
        super(WorkflowState::new, JsonUtil.copyMapper());
    }
}
