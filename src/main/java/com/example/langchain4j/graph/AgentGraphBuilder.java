package com.example.langchain4j.graph;

import com.example.langchain4j.audit.AuditEntry;
import com.example.langchain4j.audit.AuditPayloads;
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
import com.example.langchain4j.repository.entity.GraphExecution;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.action.AsyncNodeAction;
import org.bsc.langgraph4j.action.NodeAction;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.bsc.langgraph4j.serializer.StateSerializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

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
 * task_plan、task_confirm、task_execute、task_answer 组成工具任务分支。
 * finalize 统一收尾，把结果、完成标记和待确认动作写回最终状态。
 *
 * 证据优先主链路：
 *
 * START → entry → intent_plan → retrieval_probe → evidence_route
 *                                   ├─ 证据充分/计划简单 → generate → validate → finalize
 *                                   └─ 证据不足/复杂问题 → retrieval_full → generate/handoff
 * validate 失败：grounding → retrieval_full 重查；格式问题 → generate 重生成；超限 → fallback
 *
 * 条件边通过 {@link Router} 返回稳定的 route key，再映射到目标节点；
 * 图内不写业务判断，所有分流条件集中在 Router，便于单测覆盖。
 */
@Slf4j
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
    private final AuditRecorder auditRecorder;

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
                             AgentProperties properties,
                             AuditRecorder auditRecorder) {
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
        this.auditRecorder = auditRecorder;
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
            graph.addNode(NODE_ENTRY, audited(NODE_ENTRY, entryNode));
            graph.addNode(NODE_INTENT_PLAN, audited(NODE_INTENT_PLAN, intentPlanNode));
            graph.addNode(NODE_RETRIEVAL_PROBE, audited(NODE_RETRIEVAL_PROBE, retrievalProbeNode));
            graph.addNode(NODE_RETRIEVAL_FULL, audited(NODE_RETRIEVAL_FULL, retrievalNode));
            graph.addNode(NODE_GENERATE, audited(NODE_GENERATE, generateNode));
            graph.addNode(NODE_VALIDATE, audited(NODE_VALIDATE, validateNode));
            graph.addNode(NODE_FALLBACK, audited(NODE_FALLBACK, fallbackNode));
            graph.addNode(NODE_HANDOFF, audited(NODE_HANDOFF, handoffNode));
            // ---- 任务分支节点：规划 → 人工确认/直接执行 → 工具执行 → 证据回答 ----
            graph.addNode(NODE_TASK_PLAN, audited(NODE_TASK_PLAN, taskPlanNode));
            graph.addNode(NODE_TASK_CONFIRM, audited(NODE_TASK_CONFIRM, taskConfirmNode));
            graph.addNode(NODE_TASK_EXECUTE, audited(NODE_TASK_EXECUTE, taskExecuteNode));
            graph.addNode(NODE_TASK_ANSWER, audited(NODE_TASK_ANSWER, taskAnswerNode));
            // ---- 统一收尾节点：所有正常/降级/转人工路径最终都汇合到这里 ----
            graph.addNode(NODE_FINALIZE, audited(NODE_FINALIZE, finalizeNode));

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

            // 任务规划后：缺参追问 -> 直接收尾；规划失败 -> 兜底；否则先执行只读步骤
            graph.addConditionalEdges(NODE_TASK_PLAN, edge_async(router.afterTaskPlan()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_TASK_EXECUTE, NODE_TASK_EXECUTE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 确认令牌创建后：成功 -> 收尾并透出 pendingAction；失败 -> 兜底
            graph.addConditionalEdges(NODE_TASK_CONFIRM, edge_async(router.afterTaskConfirm()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 任务执行：还有未确认写步骤 -> 确认；否则 -> 基于工具证据回答
            graph.addConditionalEdges(NODE_TASK_EXECUTE, edge_async(router.afterTaskExecute()),
                    Map.of(Router.TO_TASK_CONFIRM, NODE_TASK_CONFIRM,
                            Router.TO_TASK_ANSWER, NODE_TASK_ANSWER,
                            Router.TO_FALLBACK, NODE_FALLBACK));
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
     * 将业务节点包装成带节点级审计的异步节点。
     *
     * 构图阶段只创建包装器，不执行节点。图每次进入该节点时，包装器先抓取输入摘要，
     * 再调用真实业务节点，然后根据输出中的 error 字段或异常生成审计记录，交给审计器
     * 异步落库，最后把业务节点的原始返回值交回图。
     *
     * 节点正常返回且 error 为空时记成功；error 非空时记失败，但仍原样返回输出，由后续
     * Router 决定是否走 fallback。节点抛出异常时先记失败审计再原样抛出，不改变图原有的
     * 路由和异常语义。审计实现自身失败只告警，不影响节点返回。
     *
     * @param nodeName 图节点名，同时作为 {@code ai_graph_execution.node_name}
     * @param node 真实业务节点
     * @return 带节点级审计的异步节点动作
     */
    AsyncNodeAction<WorkflowState> audited(String nodeName,
                                           NodeAction<WorkflowState> node) {
        // 构图期只创建包装器，此时不执行任何节点逻辑。
        return node_async(state -> {
            long start = System.currentTimeMillis();
            int maxLength = Math.max(1, properties.getLimits().getAuditNodeValueMaxLength());
            // 输入摘要在节点执行前抓取，避免节点修改 state 后丢失原始输入。
            Map<String, Object> input = AuditPayloads.nodeInput(state, maxLength);
            try {
                Map<String, Object> output = node.apply(state);
                String error = text(output == null ? null : output.get(WorkflowState.ERROR));
                // ERROR 非空表示业务失败，但节点返回值仍交给 Router 决定后续路由。
                safeRecord(AuditEntry.of(state.conversationId(), nodeName,
                        error.isBlank() ? GraphExecution.STATUS_SUCCESS : GraphExecution.STATUS_FAILED,
                        input, AuditPayloads.output(output, maxLength),
                        error.isBlank() ? null : error,
                        System.currentTimeMillis() - start,
                        rootCause(output, state)));
                return output;
            } catch (Exception e) {
                // 先记录失败事实，再原样抛出，保持图原有的异常处理行为。
                safeRecord(AuditEntry.of(state.conversationId(), nodeName,
                        GraphExecution.STATUS_FAILED, input, Map.of(),
                        e.getMessage(), System.currentTimeMillis() - start, state.rootCause()));
                throw e;
            }
        });
    }

    /** 节点输出中的 rootCause 优先于执行前状态，保证本轮根因写入本轮审计。 */
    private String rootCause(Map<String, Object> output, WorkflowState state) {
        String current = text(output == null ? null : output.get(WorkflowState.ROOT_CAUSE));
        return current.isBlank() ? state.rootCause() : current;
    }

    /** 审计是旁路：记录实现异常时只告警，不能反向破坏图执行。 */
    private void safeRecord(AuditEntry entry) {
        try {
            auditRecorder.record(entry);
        } catch (Exception e) {
            log.warn("[audit] node audit failed, node={}, reason={}", entry.nodeName(),
                    e.getMessage());
        }
    }

    private String text(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

}
