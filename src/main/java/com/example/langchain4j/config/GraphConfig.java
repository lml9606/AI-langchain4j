package com.example.langchain4j.config;

import com.example.langchain4j.graph.AgentGraphBuilder;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.state.WorkflowStateSerializer;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.sql.SQLException;

/**
 * 图与 Checkpoint 装配。
 * <ul>
 *   <li>优先 PostgresSaver（checkpoint 三表自动创建，服务重启后可断点恢复）；</li>
 *   <li>Postgres 不可用时按配置降级 MemorySaver（本地无 DB 也能起服务，仅丢失断点恢复能力）；</li>
 *   <li>memoryFallback=false 时 DB 不可用直接启动失败（fail-fast，生产建议）。</li>
 * </ul>
 */
@Slf4j
@Configuration
public class GraphConfig {

    @Bean
    public BaseCheckpointSaver checkpointSaver(DataSource dataSource,
                                               WorkflowStateSerializer stateSerializer,
                                               AgentProperties properties) {
        try {
            PostgresSaver saver = PostgresSaver.builder()
                    .datasource(dataSource)
                    .stateSerializer(stateSerializer)
                    .createTables(true)
                    .build();
            log.info("[graph] PostgresSaver initialized, checkpoint persistence enabled");
            return saver;
        } catch (SQLException e) {
            if (properties.getCheckpoint().isMemoryFallback()) {
                log.warn("[graph] Postgres unavailable ({}), fallback to MemorySaver: {}",
                        e.getMessage(), "checkpoint 持久化与断点恢复不可用");
                return new MemorySaver();
            }
            throw new IllegalStateException("Postgres checkpoint saver init failed and memory-fallback disabled", e);
        }
    }

    @Bean
    public CompiledGraph<WorkflowState> agentGraph(AgentGraphBuilder builder,
                                                   BaseCheckpointSaver checkpointSaver) {
        return builder.build(checkpointSaver);
    }
}
