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
        super(WorkflowState::new, JsonUtil.copyMapper());
    }
}
