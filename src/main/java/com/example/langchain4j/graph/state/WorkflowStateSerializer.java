package com.example.langchain4j.graph.state;

import com.example.langchain4j.common.util.JsonUtil;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.springframework.stereotype.Component;

/**
 * 工作流状态序列化器：Jackson 实现，用于 Checkpoint 持久化（PostgresSaver / MemorySaver）。
 * 使用独立 ObjectMapper 副本，避免污染全局 JsonUtil 实例。
 * <p>
 * 嵌套子对象（Validation/Hop record）在序列化链路上的形态：
 * <ul>
 *   <li>序列化：record 由 Jackson 自动展开为 JSON 对象（{"validated":...}），与 Map 等价，落库无碍；</li>
 *   <li>反序列化：框架按 {@code Map<String,Object>} 还原，嵌套 JSON 对象还原为 LinkedHashMap，
 *       由 {@link WorkflowState#validation()} / {@link WorkflowState#hop()} 访问器防御转换回 record；</li>
 *   <li>旧版平铺字段（validated/hopCount 等）的升级迁移在 {@link WorkflowState} 构造函数完成
 *       （super 调用前提升为嵌套子对象），本类无需也无法介入 —— 框架序列化链路（readData/writeData）均为 final。</li>
 * </ul>
 */
@Component
public class WorkflowStateSerializer extends JacksonStateSerializer<WorkflowState> {

    public WorkflowStateSerializer() {
        // 参数1：状态工厂（反序列化时用 Map 重建 WorkflowState，旧平铺字段迁移在构造函数内完成）；
        // 参数2：JsonUtil 的独立 ObjectMapper 副本，避免污染全局实例
        super(WorkflowState::new, JsonUtil.copyMapper());
    }
}
