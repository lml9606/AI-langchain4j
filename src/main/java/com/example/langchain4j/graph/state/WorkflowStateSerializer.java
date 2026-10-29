package com.example.langchain4j.graph.state;

import com.example.langchain4j.common.util.JsonUtil;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.springframework.stereotype.Component;

/**
 * 工作流状态序列化器：Jackson 实现，用于 Checkpoint 持久化（PostgresSaver / MemorySaver）。
 * 使用独立 ObjectMapper 副本，避免污染全局 JsonUtil 实例。
 */
@Component
public class WorkflowStateSerializer extends JacksonStateSerializer<WorkflowState> {

    public WorkflowStateSerializer() {
        // 参数1：状态工厂（反序列化时用 Map 重建 WorkflowState）；
        // 参数2：JsonUtil 的独立 ObjectMapper 副本，避免污染全局实例
        super(WorkflowState::new, JsonUtil.copyMapper());
    }
}
