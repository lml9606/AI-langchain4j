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
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 图装配器：节点注册 + 边连接 + 条件路由 + 编译。
 * <p>
 * 证据优先主链路：
 * <pre>
 * START → entry → intent_plan → retrieval_probe → evidence_route
 *                                   ├─ 证据充分/计划简单 → generate → validate → finalize
 *                                   └─ 证据不足/复杂问题 → retrieval_full → generate/handoff
 * validate 失败：grounding → retrieval_full 重查；格式问题 → generate 重生成；超限 → fallback
 * </pre>
 * 任务、闲聊分支保持原有能力；不再使用短查询规则、不反复调用 LLM 多跳判断。
 */
@Component
public class AgentGraphBuilder {

    public static final String NODE_ENTRY = "entry";
    public static final String NODE_INTENT_PLAN = "intent_plan";
    public static final String NODE_RETRIEVAL_PROBE = "retrieval_probe";
    public static final String NODE_RETRIEVAL_FULL = "retrieval_full";
    public static final String NODE_GENERATE = "generate";
    public static final String NODE_VALIDATE = "validate";
    public static final String NODE_FALLBACK = "fallback";
    public static final String NODE_HANDOFF = "handoff";
    public static final String NODE_TASK_PLAN = "task_plan";
    public static final String NODE_TASK_CONFIRM = "task_confirm";
    public static final String NODE_TASK_EXECUTE = "task_execute";
    public static final String NODE_TASK_ANSWER = "task_answer";
    public static final String NODE_FINALIZE = "finalize";

    private final StateSerializer<WorkflowState> stateSerializer;
    private final Router router;
    private final EntryNode entryNode;
    private final IntentPlanNode intentPlanNode;
    private final RetrievalProbeNode retrievalProbeNode;
    private final RetrievalNode retrievalNode;
    private final GenerateNode generateNode;
    private final ValidateNode validateNode;
    private final FallbackNode fallbackNode;
    private final HandoffNode handoffNode;
    private final TaskPlanNode taskPlanNode;
    private final TaskConfirmNode taskConfirmNode;
    private final TaskExecuteNode taskExecuteNode;
    private final TaskAnswerNode taskAnswerNode;
    private final FinalizeNode finalizeNode;
    private final AgentProperties properties;

    public AgentGraphBuilder(WorkflowStateSerializer stateSerializer,
                             Router router,
                             EntryNode entryNode,
                             IntentPlanNode intentPlanNode,
                             RetrievalProbeNode retrievalProbeNode,
                             RetrievalNode retrievalNode,
                             GenerateNode generateNode,
                             ValidateNode validateNode,
                             FallbackNode fallbackNode,
                             HandoffNode handoffNode,
                             TaskPlanNode taskPlanNode,
                             TaskConfirmNode taskConfirmNode,
                             TaskExecuteNode taskExecuteNode,
                             TaskAnswerNode taskAnswerNode,
                             FinalizeNode finalizeNode,
                             AgentProperties properties) {
        this.stateSerializer = stateSerializer;
        this.router = router;
        this.entryNode = entryNode;
        this.intentPlanNode = intentPlanNode;
        this.retrievalProbeNode = retrievalProbeNode;
        this.retrievalNode = retrievalNode;
        this.generateNode = generateNode;
        this.validateNode = validateNode;
        this.fallbackNode = fallbackNode;
        this.handoffNode = handoffNode;
        this.taskPlanNode = taskPlanNode;
        this.taskConfirmNode = taskConfirmNode;
        this.taskExecuteNode = taskExecuteNode;
        this.taskAnswerNode = taskAnswerNode;
        this.finalizeNode = finalizeNode;
        this.properties = properties;
    }

    public CompiledGraph<WorkflowState> build(BaseCheckpointSaver checkpointSaver) {
        StateGraph<WorkflowState> graph = new StateGraph<>(stateSerializer);
        try {
            // 主链路节点
            graph.addNode(NODE_ENTRY, node_async(entryNode));
            graph.addNode(NODE_INTENT_PLAN, node_async(intentPlanNode));
            graph.addNode(NODE_RETRIEVAL_PROBE, node_async(retrievalProbeNode));
            graph.addNode(NODE_RETRIEVAL_FULL, node_async(retrievalNode));
            graph.addNode(NODE_GENERATE, node_async(generateNode));
            graph.addNode(NODE_VALIDATE, node_async(validateNode));
            graph.addNode(NODE_FALLBACK, node_async(fallbackNode));
            graph.addNode(NODE_HANDOFF, node_async(handoffNode));
            // 任务分支节点
            graph.addNode(NODE_TASK_PLAN, node_async(taskPlanNode));
            graph.addNode(NODE_TASK_CONFIRM, node_async(taskConfirmNode));
            graph.addNode(NODE_TASK_EXECUTE, node_async(taskExecuteNode));
            graph.addNode(NODE_TASK_ANSWER, node_async(taskAnswerNode));
            graph.addNode(NODE_FINALIZE, node_async(finalizeNode));

            graph.addEdge(START, NODE_ENTRY);
            graph.addConditionalEdges(NODE_ENTRY, edge_async(router.afterEntry()),
                    Map.of(Router.TO_INTENT_PLAN, NODE_INTENT_PLAN,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            graph.addConditionalEdges(NODE_INTENT_PLAN, edge_async(router.afterIntentPlan()),
                    Map.of(Router.TO_RETRIEVAL_PROBE, NODE_RETRIEVAL_PROBE,
                            Router.TO_TASK_PLAN, NODE_TASK_PLAN,
                            Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            graph.addConditionalEdges(NODE_RETRIEVAL_PROBE, edge_async(router.afterRetrievalProbe()),
                    Map.of(Router.TO_RETRIEVAL_FULL, NODE_RETRIEVAL_FULL,
                            Router.TO_GENERATE, NODE_GENERATE));
            graph.addConditionalEdges(NODE_RETRIEVAL_FULL, edge_async(router.afterRetrievalFull()),
                    Map.of(Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_HANDOFF, NODE_HANDOFF));
            graph.addConditionalEdges(NODE_GENERATE, edge_async(router.afterGenerate()),
                    Map.of(Router.TO_VALIDATE, NODE_VALIDATE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            graph.addConditionalEdges(NODE_VALIDATE, edge_async(router.afterValidate()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_RETRIEVAL_FULL, NODE_RETRIEVAL_FULL,
                            Router.TO_FALLBACK, NODE_FALLBACK));

            // 任务线性分支
            graph.addConditionalEdges(NODE_TASK_PLAN, edge_async(router.afterTaskPlan()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_TASK_CONFIRM, NODE_TASK_CONFIRM,
                            Router.TO_TASK_EXECUTE, NODE_TASK_EXECUTE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            graph.addConditionalEdges(NODE_TASK_CONFIRM, edge_async(router.afterTaskConfirm()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            graph.addEdge(NODE_TASK_EXECUTE, NODE_TASK_ANSWER);
            graph.addEdge(NODE_TASK_ANSWER, NODE_FINALIZE);
            graph.addEdge(NODE_FALLBACK, NODE_FINALIZE);
            graph.addEdge(NODE_HANDOFF, NODE_FINALIZE);
            graph.addEdge(NODE_FINALIZE, END);

            return graph.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .recursionLimit(recursionLimit())
                    .build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("Agent graph assembly failed", e);
        }
    }

    /**
     * 递归上限：validate → generate/retrieval_full → validate 的重试环最多 maxRetries 轮，
     * 每轮最多 4 个节点，再加固定余量覆盖主链路与任务分支。
     */
    private int recursionLimit() {
        int maxRetries = Math.max(1, properties.getMaxValidateRetries());
        return maxRetries * 4 + 14;
    }

    /** 确认执行图：用户确认后只跑“执行 → 回答 → 收尾”，不重新做意图识别和规划。 */
    public CompiledGraph<WorkflowState> buildTaskConfirmGraph() {
        StateGraph<WorkflowState> graph = new StateGraph<>(stateSerializer);
        try {
            graph.addNode(NODE_TASK_EXECUTE, node_async(taskExecuteNode));
            graph.addNode(NODE_TASK_ANSWER, node_async(taskAnswerNode));
            graph.addNode(NODE_FINALIZE, node_async(finalizeNode));
            graph.addEdge(START, NODE_TASK_EXECUTE);
            graph.addEdge(NODE_TASK_EXECUTE, NODE_TASK_ANSWER);
            graph.addEdge(NODE_TASK_ANSWER, NODE_FINALIZE);
            graph.addEdge(NODE_FINALIZE, END);
            return graph.compile(CompileConfig.builder().recursionLimit(10).build());
        } catch (GraphStateException e) {
            throw new IllegalStateException("Task confirm graph assembly failed", e);
        }
    }
}
