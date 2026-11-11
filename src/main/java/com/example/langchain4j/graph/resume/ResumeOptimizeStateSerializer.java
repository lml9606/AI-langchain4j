package com.example.langchain4j.graph.resume;

import com.example.langchain4j.common.util.JsonUtil;
import org.bsc.langgraph4j.serializer.plain_text.jackson.JacksonStateSerializer;
import org.springframework.stereotype.Component;

/**
 * 简历优化状态序列化器：Jackson 实现，供 MemorySaver / 未来的 PostgresSaver 使用。
 * <p>
 * 使用独立 ObjectMapper 副本，避免污染全局 JsonUtil 实例。
 * 嵌套的 {@link ResumeOptimizeState.Validation} record 序列化为 JSON 对象，
 * 反序列化后还原为 Map，由状态访问器统一防御转换。
 */
@Component
public class ResumeOptimizeStateSerializer extends JacksonStateSerializer<ResumeOptimizeState> {

    public ResumeOptimizeStateSerializer() {
        super(ResumeOptimizeState::new, JsonUtil.copyMapper());
    }
}
