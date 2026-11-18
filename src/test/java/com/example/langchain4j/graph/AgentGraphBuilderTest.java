package com.example.langchain4j.graph;

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
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

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

    @Test
    @DisplayName("注册证据优先主链路与任务分支后主图可编译")
    void buildGraphWithEvidenceFirstFlow() {
        AgentProperties properties = new AgentProperties();
        AgentGraphBuilder builder = new AgentGraphBuilder(
                new WorkflowStateSerializer(),
                new Router(properties),
                entryNode, intentPlanNode, retrievalProbeNode, retrievalNode,
                generateNode, validateNode, fallbackNode, handoffNode,
                taskPlanNode, taskConfirmNode, taskExecuteNode, taskAnswerNode, finalizeNode,
                properties);

        CompiledGraph<WorkflowState> graph = builder.build(new MemorySaver());

        assertThat(graph).isNotNull();
        assertThat(AgentGraphBuilder.NODE_INTENT_PLAN).isEqualTo("intent_plan");
        assertThat(AgentGraphBuilder.NODE_RETRIEVAL_PROBE).isEqualTo("retrieval_probe");
        assertThat(AgentGraphBuilder.NODE_RETRIEVAL_FULL).isEqualTo("retrieval_full");
        assertThat(builder.buildTaskConfirmGraph()).isNotNull();
    }
}
