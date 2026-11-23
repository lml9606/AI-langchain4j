package com.example.langchain4j.graph;

import com.example.langchain4j.audit.AuditEntry;
import com.example.langchain4j.audit.AuditRecorder;
import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.common.nodes.FinalizeNode;
import com.example.langchain4j.graph.qa.edges.Router;
import com.example.langchain4j.graph.qa.nodes.EntryNode;
import com.example.langchain4j.graph.qa.nodes.FallbackNode;
import com.example.langchain4j.graph.qa.nodes.GenerateNode;
import com.example.langchain4j.graph.qa.nodes.HandoffNode;
import com.example.langchain4j.graph.qa.nodes.IntentPlanNode;
import com.example.langchain4j.graph.qa.nodes.RetrievalNode;
import com.example.langchain4j.graph.qa.nodes.RetrievalProbeNode;
import com.example.langchain4j.graph.qa.nodes.ValidateNode;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.state.WorkflowStateSerializer;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.graph.task.nodes.TaskConfirmNode;
import com.example.langchain4j.graph.task.nodes.TaskExecuteNode;
import com.example.langchain4j.graph.task.nodes.TaskPlanNode;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 主图装配测试：校验证据优先链路与任务分支在启动期可编译。 */
@ExtendWith(MockitoExtension.class)
class AgentGraphBuilderTest {

    @Mock
    private EntryNode entryNode;
    @Mock
    private IntentPlanNode intentPlanNode;
    @Mock
    private RetrievalProbeNode retrievalProbeNode;
    @Mock
    private RetrievalNode retrievalNode;
    @Mock
    private GenerateNode generateNode;
    @Mock
    private ValidateNode validateNode;
    @Mock
    private FallbackNode fallbackNode;
    @Mock
    private HandoffNode handoffNode;
    @Mock
    private TaskPlanNode taskPlanNode;
    @Mock
    private TaskConfirmNode taskConfirmNode;
    @Mock
    private TaskExecuteNode taskExecuteNode;
    @Mock
    private TaskAnswerNode taskAnswerNode;
    @Mock
    private FinalizeNode finalizeNode;
    @Mock
    private AuditRecorder auditRecorder;

    @Test
    @DisplayName("注册证据优先主链路与任务分支后主图可编译")
    void buildGraphWithEvidenceFirstFlow() {
        AgentProperties properties = new AgentProperties();
        AgentGraphBuilder builder = builder(properties);

        CompiledGraph<WorkflowState> graph = builder.build(new MemorySaver());

        assertThat(graph).isNotNull();
        assertThat(AgentGraphBuilder.NODE_INTENT_PLAN).isEqualTo("intent_plan");
        assertThat(AgentGraphBuilder.NODE_RETRIEVAL_PROBE).isEqualTo("retrieval_probe");
        assertThat(AgentGraphBuilder.NODE_RETRIEVAL_FULL).isEqualTo("retrieval_full");
    }

    @Test
    @DisplayName("节点统一审计：成功返回时记录节点名、会话、状态与耗时")
    void auditedNodeRecordsSuccess() throws Exception {
        AgentProperties properties = new AgentProperties();
        AgentGraphBuilder builder = builder(properties);
        when(entryNode.apply(any())).thenReturn(Map.of("ok", true));
        AsyncNodeAction<WorkflowState> action = builder.audited("entry", entryNode);
        WorkflowState state = new WorkflowState(Map.of(
                WorkflowState.CONVERSATION_ID, "c1",
                WorkflowState.QUERY, "hello"));

        Map<String, Object> output = action.apply(state).get();

        assertThat(output).containsEntry("ok", true);
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRecorder).record(captor.capture());
        AuditEntry entry = captor.getValue();
        assertThat(entry.nodeName()).isEqualTo("entry");
        assertThat(entry.conversationId()).isEqualTo("c1");
        assertThat(entry.status()).isEqualTo("SUCCESS");
        assertThat(entry.costMs()).isNotNull();
        assertThat(entry.input()).containsEntry("query", "hello");
    }

    @Test
    @DisplayName("节点统一审计：节点抛异常时记录 FAILED 后原样上抛")
    void auditedNodeRecordsFailureAndRethrows() {
        AgentProperties properties = new AgentProperties();
        AgentGraphBuilder builder = builder(properties);
        when(entryNode.apply(any())).thenThrow(new IllegalStateException("boom"));
        AsyncNodeAction<WorkflowState> action = builder.audited("entry", entryNode);
        WorkflowState state = new WorkflowState(Map.of(
                WorkflowState.CONVERSATION_ID, "c1",
                WorkflowState.QUERY, "hello"));

        assertThatThrownBy(() -> action.apply(state).get())
                .hasCauseInstanceOf(IllegalStateException.class)
                .hasMessageContaining("boom");
        ArgumentCaptor<AuditEntry> captor = ArgumentCaptor.forClass(AuditEntry.class);
        verify(auditRecorder).record(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo("FAILED");
        assertThat(captor.getValue().errorMsg()).isEqualTo("boom");
    }

    private AgentGraphBuilder builder(AgentProperties properties) {
        return new AgentGraphBuilder(
                new WorkflowStateSerializer(),
                new Router(properties),
                entryNode, intentPlanNode, retrievalProbeNode, retrievalNode,
                generateNode, validateNode, fallbackNode, handoffNode,
                taskPlanNode, taskConfirmNode, taskExecuteNode, taskAnswerNode, finalizeNode,
                properties, auditRecorder);
    }
}
