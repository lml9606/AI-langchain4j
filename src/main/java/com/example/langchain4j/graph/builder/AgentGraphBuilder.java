package com.example.langchain4j.graph.builder;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.common.nodes.FinalizeNode;
import com.example.langchain4j.graph.constant.GraphNodes;
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
import com.example.langchain4j.graph.task.nodes.ReactPlanNode;
import com.example.langchain4j.graph.task.nodes.ReactToolNode;
import com.example.langchain4j.graph.task.nodes.TaskAnswerNode;
import com.example.langchain4j.graph.task.nodes.TaskConfirmNode;
import com.example.langchain4j.graph.task.nodes.TaskQuotaReadNode;
import com.example.langchain4j.graph.task.nodes.TaskReadNode;
import com.example.langchain4j.graph.task.nodes.TaskResolveNode;
import com.example.langchain4j.graph.task.nodes.TaskTransactionReadNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.serializer.StateSerializer;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Component;

import java.util.Map;

import static com.example.langchain4j.graph.constant.GraphNodes.NODE_ENTRY;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_FALLBACK;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_FINALIZE;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_GENERATE;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_HANDOFF;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_INTENT_PLAN;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_REACT_PLAN;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_REACT_TOOL;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_RETRIEVAL_FULL;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_RETRIEVAL_PROBE;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_TASK_ANSWER;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_TASK_CONFIRM;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_TASK_READ_QUOTA;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_TASK_READ_TRANSACTION;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_TASK_READ;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_TASK_RESOLVE;
import static com.example.langchain4j.graph.constant.GraphNodes.NODE_VALIDATE;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;
import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;

/**
 * 图装配器：节点注册 + 边连接 + 条件路由 + 编译。
 *
 * 节点职责：
 * entry 负责输入清洗、会话状态重置和空输入兜底。
 * intent_plan 一次结构化调用完成意图分类以及主查询、子查询规划。
 * retrieval_probe 轻量探针检索，判断是否需要升级完整检索。
 * retrieval_full 按计划多查询召回，或质检 grounding 失败后的纠错重查。
 * generate 按意图、复杂度和证据组装 prompt 并生成回答。
 * validate 执行规则、证据和 LLM 三层质检，决定通过、重生成、重查或兜底。
 * fallback 在异常或重试耗尽时给出确定性降级回答。
 * handoff 在知识库确实无证据时转人工并标记根因。
 * task_resolve 同时完成读写判断和参数抽取，之后按读/写分流：
 * 读链路 task_read → task_answer；报名关联额度时由 task_read_quota 读额度表，
 * 报名/额度关联交易时由 task_read_transaction 读交易表；写链路先 task_confirm 生成令牌并收尾，
 * 用户确认后由 TaskConfirmService 直接执行 task_write → task_answer → finalize。
 * ReAct 只读诊断由 task_resolve 标记 REACT 后进入 react_plan → react_tool 循环，
 * 只在白名单只读工具间选择，达到步数、重复调用、无数据或致命失败时停止并转到 task_answer。
 * finalize 统一收尾，把结果、完成标记和待确认动作写回最终状态。
 *
 * 证据优先主链路：
 *
 * START → entry → intent_plan → retrieval_probe
 * ├─ 证据充分/计划简单 → generate → validate → finalize
 * └─ 证据不足/复杂问题 → retrieval_full → generate/handoff
 * validate 失败：grounding → retrieval_full 重查；格式问题 → generate 重生成；超限 → fallback
 *
 * 条件边通过 {@link Router} 返回稳定的 route key，再映射到目标节点；
 * 图内不写业务判断，所有分流条件集中在 Router，便于单测覆盖。
 */
@Slf4j
@Component
public class AgentGraphBuilder {

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
    private final TaskResolveNode taskResolveNode;
    private final TaskConfirmNode taskConfirmNode;
    private final TaskReadNode taskReadNode;
    private final TaskQuotaReadNode taskQuotaReadNode;
    private final TaskTransactionReadNode taskTransactionReadNode;
    private final TaskAnswerNode taskAnswerNode;
    private final ReactPlanNode reactPlanNode;
    private final ReactToolNode reactToolNode;
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
                             TaskResolveNode taskResolveNode,
                             TaskConfirmNode taskConfirmNode,
                             TaskReadNode taskReadNode,
                             TaskQuotaReadNode taskQuotaReadNode,
                             TaskTransactionReadNode taskTransactionReadNode,
                             TaskAnswerNode taskAnswerNode,
                             ReactPlanNode reactPlanNode,
                             ReactToolNode reactToolNode,
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
        this.taskResolveNode = taskResolveNode;
        this.taskConfirmNode = taskConfirmNode;
        this.taskReadNode = taskReadNode;
        this.taskQuotaReadNode = taskQuotaReadNode;
        this.taskTransactionReadNode = taskTransactionReadNode;
        this.taskAnswerNode = taskAnswerNode;
        this.reactPlanNode = reactPlanNode;
        this.reactToolNode = reactToolNode;
        this.finalizeNode = finalizeNode;
        this.properties = properties;
    }

    /**
     * 编译主图。
     *
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
            // ---- 任务分支节点：解析读写与参数 → 读执行/写确认 → 证据回答 ----
            graph.addNode(NODE_TASK_RESOLVE, node_async(taskResolveNode));
            graph.addNode(NODE_TASK_CONFIRM, node_async(taskConfirmNode));
            graph.addNode(NODE_TASK_READ, node_async(taskReadNode));
            graph.addNode(NODE_TASK_READ_QUOTA, node_async(taskQuotaReadNode));
            graph.addNode(NODE_TASK_READ_TRANSACTION, node_async(taskTransactionReadNode));
            graph.addNode(NODE_TASK_ANSWER, node_async(taskAnswerNode));
            // ---- ReAct 只读诊断：规划 → 执行 → 路由回规划，直到 final/停止条件 ----
            graph.addNode(NODE_REACT_PLAN, node_async(reactPlanNode));
            graph.addNode(NODE_REACT_TOOL, node_async(reactToolNode));
            // ---- 统一收尾节点：所有正常/降级/转人工路径最终都汇合到这里 ----
            graph.addNode(NODE_FINALIZE, node_async(finalizeNode));

            // 入口守卫：空输入/清洗失败 -> fallback；正常 -> 意图与规划
            graph.addEdge(START, NODE_ENTRY);
            graph.addConditionalEdges(NODE_ENTRY, edge_async(router.afterEntry()),
                    Map.of(Router.TO_INTENT_PLAN, NODE_INTENT_PLAN,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 意图分流：知识问答/代码生成 -> 探针检索；任务 -> 解析读写与参数；闲聊 -> 直接生成；拒绝 -> 兜底
            graph.addConditionalEdges(NODE_INTENT_PLAN, edge_async(router.afterIntentPlan()),
                    Map.of(Router.TO_RETRIEVAL_PROBE, NODE_RETRIEVAL_PROBE,
                            Router.TO_TASK_RESOLVE, NODE_TASK_RESOLVE,
                            Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 探针分流：证据充分且计划简单 -> 直接生成；否则升级完整检索
            graph.addConditionalEdges(NODE_RETRIEVAL_PROBE,
                    edge_async(router.afterRetrievalProbe()),
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

            // 任务解析后：缺参追问 -> 收尾；读 -> task_read；写 -> task_confirm；
            // 受控只读诊断 -> react_plan；失败 -> 兜底
            graph.addConditionalEdges(NODE_TASK_RESOLVE, edge_async(router.afterTaskResolve()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_TASK_READ, NODE_TASK_READ,
                            Router.TO_TASK_CONFIRM, NODE_TASK_CONFIRM,
                            Router.TO_REACT_PLAN, NODE_REACT_PLAN,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // ReAct 规划后：调用白名单工具 -> react_tool；final/达到上限 -> 回答；配置错误 -> 兜底
            graph.addConditionalEdges(NODE_REACT_PLAN, edge_async(router.afterReactPlan()),
                    Map.of(Router.TO_REACT_TOOL, NODE_REACT_TOOL,
                            Router.TO_TASK_ANSWER, NODE_TASK_ANSWER,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // ReAct 工具执行后：继续 -> react_plan；已有结论/停止 -> task_answer；前置错误 -> 兜底
            graph.addConditionalEdges(NODE_REACT_TOOL, edge_async(router.afterReactTool()),
                    Map.of(Router.TO_REACT_PLAN, NODE_REACT_PLAN,
                            Router.TO_TASK_ANSWER, NODE_TASK_ANSWER,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 确认令牌创建后：成功 -> 收尾并透出 pendingAction；失败 -> 兜底
            graph.addConditionalEdges(NODE_TASK_CONFIRM, edge_async(router.afterTaskConfirm()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 第一段只读后：需要关联额度/交易且已命中来源 → 第二段查询；否则直接回答
            graph.addConditionalEdges(NODE_TASK_READ, edge_async(router.afterTaskRead()),
                    Map.of(Router.TO_TASK_READ_QUOTA, NODE_TASK_READ_QUOTA,
                            Router.TO_TASK_READ_TRANSACTION, NODE_TASK_READ_TRANSACTION,
                            Router.TO_TASK_ANSWER, NODE_TASK_ANSWER));
            // 第二段额度/交易读取后汇合到回答节点；写节点只在用户确认后由 TaskConfirmService 直接调用
            graph.addEdge(NODE_TASK_READ_QUOTA, NODE_TASK_ANSWER);
            graph.addEdge(NODE_TASK_READ_TRANSACTION, NODE_TASK_ANSWER);
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
     * 每轮最多 4 个节点；ReAct 每步经过 react_plan + react_tool 两个节点，再预留一次
     * 停止规划节点。最后加固定余量覆盖主链路与任务分支。
     */
    private int recursionLimit() {
        int maxRetries = Math.max(1, properties.getMaxValidateRetries());
        int maxReactSteps = Math.max(1, properties.getReact().getMaxSteps());
        return maxRetries * 4 + maxReactSteps * 2 + 18;
    }

}
