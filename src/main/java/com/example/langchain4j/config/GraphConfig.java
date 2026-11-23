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
 *
 * - 优先 PostgresSaver（框架自建 lg4jthread / lg4jcheckpoint 两张表，服务重启后可断点恢复）；
 * - Postgres 不可用时按配置降级 MemorySaver（本地无 DB 也能起服务，仅丢失断点恢复能力）；
 * - memoryFallback=false 时 DB 不可用直接启动失败（fail-fast，生产建议）。
 *
 * 注意：lg4j 两张表的表名与结构由 langgraph4j-postgres-saver 框架硬编码（每次启动
 * createTables(true) 重跑 DDL），不可改表名、不可合并，业务扩展一律走视图或自有审计表。
 */
@Slf4j
@Configuration
public class GraphConfig {

    /**
     * 主图 Checkpoint Saver（对话工作流的节点快照持久化）。
     *
     * - 正常：PostgresSaver，快照落 lg4jcheckpoint 表（JSONB），重启后可断点恢复；
     * - 降级：DB 不可用且 checkpoint.memory-fallback=true 时返回 MemorySaver，
     *       仅丢失持久化与断点恢复能力，服务可用；
     * - fail-fast：DB 不可用且 memory-fallback=false 时抛出异常阻断启动。
     *
     * @param dataSource      数据源（PostgreSQL，ai_agent 库）
     * @param stateSerializer WorkflowState 序列化器（Jackson 实现，checkpoint JSONB 落库/读取用）
     * @param properties      应用配置（读取 checkpoint.memory-fallback 降级开关）
     * @return 主图使用的 Checkpoint Saver（PostgresSaver，或降级后的 MemorySaver）
     * @throws IllegalStateException DB 不可用且 memory-fallback=false 时抛出（fail-fast）
     */
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

    /**
     * 主工作流图（对话 Agent）。
     *
     * @param builder         图装配器（节点 + 边 + 条件路由，见 AgentGraphBuilder）
     * @param checkpointSaver 节点快照持久化器（见 {@link #checkpointSaver}）；
     *                        调用方以 RunnableConfig.threadId(会话 ID) 执行，实现会话级隔离
     * @return 编译后的主图；AgentService 持 config 调用 invoke，支持断点续跑
     */
    @Bean
    public CompiledGraph<WorkflowState> agentGraph(AgentGraphBuilder builder,
                                                   BaseCheckpointSaver checkpointSaver) {
        return builder.build(checkpointSaver);
    }

}
