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
 * 节点职责：
 * <ul>
 *   <li>{@code entry}：输入清洗、会话状态重置、空输入兜底；</li>
 *   <li>{@code intent_plan}：一次结构化调用完成意图分类 + 主查询/子查询规划；</li>
 *   <li>{@code retrieval_probe}：轻量探针检索，判断是否需要升级完整检索；</li>
 *   <li>{@code retrieval_full}：按计划多查询召回，或质检 grounding 失败后的纠错重查；</li>
 *   <li>{@code generate}：按意图/复杂度/证据组装 prompt 并生成回答；</li>
 *   <li>{@code validate}：规则、证据、LLM 三层质检，决定通过/重生成/重查/兜底；</li>
 *   <li>{@code fallback}：异常或重试耗尽时的确定性降级回答；</li>
 *   <li>{@code handoff}：知识库确实无证据时转人工并标记根因；</li>
 *   <li>{@code task_plan / task_confirm / task_execute / task_answer}：工具任务分支；</li>
 *   <li>{@code finalize}：统一收尾，把结果、完成标记、待确认动作写回最终状态。</li>
 * </ul>
 * <p>
 * 证据优先主链路：
 * <pre>
 * START → entry → intent_plan → retrieval_probe → evidence_route
 *                                   ├─ 证据充分/计划简单 → generate → validate → finalize
 *                                   └─ 证据不足/复杂问题 → retrieval_full → generate/handoff
 * validate 失败：grounding → retrieval_full 重查；格式问题 → generate 重生成；超限 → fallback
 * </pre>
 * 条件边通过 {@link Router} 返回稳定的 route key，再映射到目标节点；
 * 图内不写业务判断，所有分流条件集中在 Router，便于单测覆盖。
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

    /**
     * 编译主图。
     * <p>
     * checkpointSaver 按 threadId（= conversationId）保存每个节点执行后的完整状态，
     * 用于断点恢复与执行位置排查；recursionLimit 见 {@link #recursionLimit()}。
     */
    public CompiledGraph<WorkflowState> build(BaseCheckpointSaver checkpointSaver) {
        StateGraph<WorkflowState> graph = new StateGraph<>(stateSerializer);
        try {
            // ---- 主链路节点：问答、检索、生成、质检、兜底、转人工 ----
            graph.addNode(NODE_ENTRY, node_async(entryNode));
            graph.addNode(NODE_INTENT_PLAN, node_async(intentPlanNode));
            graph.addNode(NODE_RETRIEVAL_PROBE, node_async(retrievalProbeNode));
            graph.addNode(NODE_RETRIEVAL_FULL, node_async(retrievalNode));
            graph.addNode(NODE_GENERATE, node_async(generateNode));
            graph.addNode(NODE_VALIDATE, node_async(validateNode));
            graph.addNode(NODE_FALLBACK, node_async(fallbackNode));
            graph.addNode(NODE_HANDOFF, node_async(handoffNode));
            // ---- 任务分支节点：规划 → 人工确认/直接执行 → 工具执行 → 证据回答 ----
            graph.addNode(NODE_TASK_PLAN, node_async(taskPlanNode));
            graph.addNode(NODE_TASK_CONFIRM, node_async(taskConfirmNode));
            graph.addNode(NODE_TASK_EXECUTE, node_async(taskExecuteNode));
            graph.addNode(NODE_TASK_ANSWER, node_async(taskAnswerNode));
            // ---- 统一收尾节点：所有正常/降级/转人工路径最终都汇合到这里 ----
            graph.addNode(NODE_FINALIZE, node_async(finalizeNode));

            // 入口守卫：空输入/清洗失败 -> fallback；正常 -> 意图与规划
            graph.addEdge(START, NODE_ENTRY);
            graph.addConditionalEdges(NODE_ENTRY, edge_async(router.afterEntry()),
                    Map.of(Router.TO_INTENT_PLAN, NODE_INTENT_PLAN,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 意图分流：知识问答/代码生成 -> 探针检索；任务 -> 任务规划；闲聊 -> 直接生成；拒绝 -> 兜底
            graph.addConditionalEdges(NODE_INTENT_PLAN, edge_async(router.afterIntentPlan()),
                    Map.of(Router.TO_RETRIEVAL_PROBE, NODE_RETRIEVAL_PROBE,
                            Router.TO_TASK_PLAN, NODE_TASK_PLAN,
                            Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 探针分流：证据充分且计划简单 -> 直接生成；否则升级完整检索
            graph.addConditionalEdges(NODE_RETRIEVAL_PROBE, edge_async(router.afterRetrievalProbe()),
                    Map.of(Router.TO_RETRIEVAL_FULL, NODE_RETRIEVAL_FULL,
                            Router.TO_GENERATE, NODE_GENERATE));
            // 完整检索后：知识问答仍为空 -> 转人工；有证据 -> 生成
            graph.addConditionalEdges(NODE_RETRIEVAL_FULL, edge_async(router.afterRetrievalFull()),
                    Map.of(Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_HANDOFF, NODE_HANDOFF));
            // 生成后：节点写 error -> 兜底；正常 -> 质检
            graph.addConditionalEdges(NODE_GENERATE, edge_async(router.afterGenerate()),
                    Map.of(Router.TO_VALIDATE, NODE_VALIDATE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 质检后：通过 -> 收尾；grounding 失败 -> 完整重查；格式失败 -> 重生成；重试耗尽 -> 兜底
            graph.addConditionalEdges(NODE_VALIDATE, edge_async(router.afterValidate()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_RETRIEVAL_FULL, NODE_RETRIEVAL_FULL,
                            Router.TO_FALLBACK, NODE_FALLBACK));

            // 任务规划后：缺参追问 -> 直接收尾；规划失败 -> 兜底；写工具 -> 确认；只读工具 -> 执行
            graph.addConditionalEdges(NODE_TASK_PLAN, edge_async(router.afterTaskPlan()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_TASK_CONFIRM, NODE_TASK_CONFIRM,
                            Router.TO_TASK_EXECUTE, NODE_TASK_EXECUTE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 确认令牌创建后：成功 -> 收尾并透出 pendingAction；失败 -> 兜底
            graph.addConditionalEdges(NODE_TASK_CONFIRM, edge_async(router.afterTaskConfirm()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 任务执行 -> 基于工具证据回答 -> 收尾；兜底/转人工同样汇合到收尾
            graph.addEdge(NODE_TASK_EXECUTE, NODE_TASK_ANSWER);
            graph.addEdge(NODE_TASK_ANSWER, NODE_FINALIZE);
            graph.addEdge(NODE_FALLBACK, NODE_FINALIZE);
            graph.addEdge(NODE_HANDOFF, NODE_FINALIZE);
            graph.addEdge(NODE_FINALIZE, END);

            // checkpoint：按 threadId 在节点边界落盘；recursionLimit：覆盖 validate 重试环 + 分支余量
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

    /**
     * 确认执行图：用户确认后只跑“执行 → 回答 → 收尾”，不重新做意图识别和规划。
     * <p>
     * 独立小图的原因：确认后的计划已经过人工审核，必须严格按 TASK_PLAN 执行，
     * 不能再让模型重新规划，否则会出现“用户确认 A、系统执行 B”的越权风险。
     * 该图不挂 checkpointSaver：确认令牌本身一次性，执行结果由 AgentService 落库。
     */
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
