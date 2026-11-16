package com.example.langchain4j.graph;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.common.nodes.FinalizeNode;
import com.example.langchain4j.graph.qa.edges.Router;
import com.example.langchain4j.graph.qa.nodes.DecomposeNode;
import com.example.langchain4j.graph.qa.nodes.EntryNode;
import com.example.langchain4j.graph.qa.nodes.FallbackNode;
import com.example.langchain4j.graph.qa.nodes.GenerateNode;
import com.example.langchain4j.graph.qa.nodes.HandoffNode;
import com.example.langchain4j.graph.qa.nodes.HopCheckNode;
import com.example.langchain4j.graph.qa.nodes.IntentNode;
import com.example.langchain4j.graph.qa.nodes.RetrievalNode;
import com.example.langchain4j.graph.qa.nodes.ValidateNode;
import com.example.langchain4j.graph.resume.nodes.ResumeTaskNode;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.graph.task.nodes.TaskConfirmNode;
import com.example.langchain4j.graph.task.nodes.TaskExecuteNode;
import com.example.langchain4j.graph.task.nodes.TaskPlanNode;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.state.WorkflowStateSerializer;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.checkpoint.MemorySaver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 主图装配测试：校验节点注册、任务分支边与条件路由映射在启动期可编译。
 */
@ExtendWith(MockitoExtension.class)
class AgentGraphBuilderTest {

    @Mock
    private EntryNode entryNode;
    @Mock
    private IntentNode intentNode;
    @Mock
    private DecomposeNode decomposeNode;
    @Mock
    private RetrievalNode retrievalNode;
    @Mock
    private HopCheckNode hopCheckNode;
    @Mock
    private GenerateNode generateNode;
    @Mock
    private ValidateNode validateNode;
    @Mock
    private FallbackNode fallbackNode;
    @Mock
    private HandoffNode handoffNode;
    @Mock
    private ResumeTaskNode resumeTaskNode;
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
    @DisplayName("注册任务规划/执行/回答节点后主图可编译")
    void buildGraphWithTaskBranch() {
        AgentGraphBuilder builder = new AgentGraphBuilder(
                new WorkflowStateSerializer(),
                new Router(new AgentProperties()),
                entryNode, intentNode, decomposeNode, retrievalNode, hopCheckNode,
                generateNode, validateNode, fallbackNode, handoffNode, resumeTaskNode,
                taskPlanNode, taskConfirmNode, taskExecuteNode, taskAnswerNode, finalizeNode,
                new AgentProperties());

        CompiledGraph<WorkflowState> graph = builder.build(new MemorySaver());

        assertThat(graph).isNotNull();
        assertThat(AgentGraphBuilder.NODE_TASK_PLAN).isEqualTo("task_plan");
        assertThat(AgentGraphBuilder.NODE_TASK_CONFIRM).isEqualTo("task_confirm");
        assertThat(AgentGraphBuilder.NODE_TASK_EXECUTE).isEqualTo("task_execute");
        assertThat(AgentGraphBuilder.NODE_TASK_ANSWER).isEqualTo("task_answer");
        assertThat(builder.buildTaskConfirmGraph()).isNotNull();
    }
}
