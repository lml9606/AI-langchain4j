package com.example.langchain4j.graph;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.edges.Router;
import com.example.langchain4j.graph.nodes.DecomposeNode;
import com.example.langchain4j.graph.nodes.EntryNode;
import com.example.langchain4j.graph.nodes.FallbackNode;
import com.example.langchain4j.graph.nodes.FinalizeNode;
import com.example.langchain4j.graph.nodes.GenerateNode;
import com.example.langchain4j.graph.nodes.HandoffNode;
import com.example.langchain4j.graph.nodes.HopCheckNode;
import com.example.langchain4j.graph.nodes.IntentNode;
import com.example.langchain4j.graph.nodes.RetrievalNode;
import com.example.langchain4j.graph.nodes.ValidateNode;
import com.example.langchain4j.graph.state.WorkflowState;
import com.example.langchain4j.graph.state.WorkflowStateSerializer;
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
 * 图结构：START → entry(守卫) ⇢ intent(意图) → decompose(问题分解) → retrieval(检索)
 *        ⇄ hopCheck(多跳判断，有限补查环) → generate(生成) → validate(校验)
 *        ⇄（Corrective RAG 有限重试环）→ fallback(兜底) → finalize(收尾) → END
 *        （entry 校验失败 → 直接 fallback，跳过 intent，省一次 LLM 调用）
 *        （代码生成意图跳过 decompose/hopCheck，直接 retrieval → generate）
 *        （检索无结果：知识问答先经多跳换问法补查，仍空才转人工，不进入 LLM 生成）
 * <p>
 * 说明：
 * <ul>
 *   <li>checkpointSaver 由调用方（config 层）传入，Postgres 不可用时外部降级为 MemorySaver</li>
 *   <li>图装配失败属于启动期配置错误，直接 fail-fast 抛异常，不吞掉</li>
 *   <li>recursionLimit 基于多跳上限与重试上限动态计算，防御路由死循环</li>
 * </ul>
 */
@Component
public class AgentGraphBuilder {

    /** 节点名常量：路由映射与节点注册共用，避免字符串漂移 */
    public static final String NODE_ENTRY = "entry";
    public static final String NODE_INTENT = "intent";
    public static final String NODE_DECOMPOSE = "decompose";
    public static final String NODE_RETRIEVAL = "retrieval";
    public static final String NODE_HOP_CHECK = "hop_check";
    public static final String NODE_GENERATE = "generate";
    public static final String NODE_VALIDATE = "validate";
    public static final String NODE_FALLBACK = "fallback";
    public static final String NODE_HANDOFF = "handoff";
    public static final String NODE_FINALIZE = "finalize";

    private final StateSerializer<WorkflowState> stateSerializer;
    private final Router router;
    private final EntryNode entryNode;
    private final IntentNode intentNode;
    private final DecomposeNode decomposeNode;
    private final RetrievalNode retrievalNode;
    private final HopCheckNode hopCheckNode;
    private final GenerateNode generateNode;
    private final ValidateNode validateNode;
    private final FallbackNode fallbackNode;
    private final HandoffNode handoffNode;
    private final FinalizeNode finalizeNode;
    private final AgentProperties properties;

    public AgentGraphBuilder(WorkflowStateSerializer stateSerializer,
                             Router router,
                             EntryNode entryNode,
                             IntentNode intentNode,
                             DecomposeNode decomposeNode,
                             RetrievalNode retrievalNode,
                             HopCheckNode hopCheckNode,
                             GenerateNode generateNode,
                             ValidateNode validateNode,
                             FallbackNode fallbackNode,
                             HandoffNode handoffNode,
                             FinalizeNode finalizeNode,
                             AgentProperties properties) {
        this.stateSerializer = stateSerializer;
        this.router = router;
        this.entryNode = entryNode;
        this.intentNode = intentNode;
        this.decomposeNode = decomposeNode;
        this.retrievalNode = retrievalNode;
        this.hopCheckNode = hopCheckNode;
        this.generateNode = generateNode;
        this.validateNode = validateNode;
        this.fallbackNode = fallbackNode;
        this.handoffNode = handoffNode;
        this.finalizeNode = finalizeNode;
        this.properties = properties;
    }

    /**
     * 组装并编译图。
     *
     * @param checkpointSaver checkpoint 持久化器（PG 不可用时可传 MemorySaver）
     * @return 编译后的可执行图
     */
    public CompiledGraph<WorkflowState> build(BaseCheckpointSaver checkpointSaver) {
        // 步骤1：以状态序列化器创建图（checkpoint 落库/恢复时依赖它做序列化）
        StateGraph<WorkflowState> graph = new StateGraph<>(stateSerializer);
        try {
            // 步骤2：注册全部节点（异步包装，节点内部各自负责防御与日志）
            // entry 守卫节点：空输入拦截 + 长度截断/控制字符清理 + 重试与多跳计数归零 + 种入默认子查询
            graph.addNode(NODE_ENTRY, node_async(entryNode));
            // intent 意图识别：knowledge_qa / code_generate / reject（失败默认 knowledge_qa）
            graph.addNode(NODE_INTENT, node_async(intentNode));
            // decompose 问题分解：复合问题拆子查询（失败/空输出降级为单查询，等同未分解）
            graph.addNode(NODE_DECOMPOSE, node_async(decomposeNode));
            // retrieval 知识检索：pgvector 语义检索，三种模式（首轮子查询遍历 / 多跳补查 / 纠错重查）
            graph.addNode(NODE_RETRIEVAL, node_async(retrievalNode));
            // hop_check 多跳判断：上下文是否足以回答（不足且未达上限 → 回检索补查）
            graph.addNode(NODE_HOP_CHECK, node_async(hopCheckNode));
            // generate 主生成：按意图选 prompt，结合检索上下文与多轮历史生成回答（异常转兜底）
            graph.addNode(NODE_GENERATE, node_async(generateNode));
            // validate 质量校验：规则层（非空/长度/自曝错误）+ LLM 质检层（防幻觉）双门禁
            graph.addNode(NODE_VALIDATE, node_async(validateNode));
            // fallback 兜底：所有异常路径的最终出口，输出固定降级答案，保证图永远有输出
            graph.addNode(NODE_FALLBACK, node_async(fallbackNode));
            // handoff 转人工：检索无结果时的出口，返回固定提示，不进入 LLM 生成
            graph.addNode(NODE_HANDOFF, node_async(handoffNode));
            // finalize 收尾：结果兜底组装（防空输出）+ 标记流程完成（持久化由 service 层负责）
            graph.addNode(NODE_FINALIZE, node_async(finalizeNode));

            // 步骤3：连接主链路与条件路由（注释格式：来源路由 → 条件 ⇒ 目标节点）
            // START 直连 entry：图入口无条件进入守卫节点（空输入校验 + 清洗 + 计数归零 + 种入默认子查询）
            graph.addEdge(START, NODE_ENTRY);
            // entry 守卫路由：校验失败（空输入）→ fallback 兜底；通过 → intent 意图识别
            //                （失败跳过 intent，省一次 LLM 调用）
            graph.addConditionalEdges(NODE_ENTRY, edge_async(router.afterEntry()),
                    Map.of(Router.TO_INTENT, NODE_INTENT,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // intent 意图路由：knowledge_qa → decompose 分解；code_generate → retrieval 直接检索；
            //                 reject/未知意图/异常 → fallback 兜底拒绝回答
            graph.addConditionalEdges(NODE_INTENT, edge_async(router.afterIntent()),
                    Map.of(Router.TO_DECOMPOSE, NODE_DECOMPOSE,
                            Router.TO_RETRIEVAL, NODE_RETRIEVAL,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // decompose 直连 retrieval：分解完成（含降级单查询）无条件进入检索
            graph.addEdge(NODE_DECOMPOSE, NODE_RETRIEVAL);
            // retrieval 检索路由：knowledge_qa → hop_check 多跳判断（首轮空上下文也进，换问法补查）；
            //                  code_generate：空上下文 → handoff 转人工；有资料 → generate 直接生成
            graph.addConditionalEdges(NODE_RETRIEVAL, edge_async(router.afterRetrieval()),
                    Map.of(Router.TO_HOP_CHECK, NODE_HOP_CHECK,
                            Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_HANDOFF, NODE_HANDOFF));
            // hop_check 多跳路由（有限多跳环）：足够/超限 → generate 生成；足够但上下文仍空 → handoff 转人工（空资料不许生成）；
            //                不足且未达上限 → retrieval 回检索补查（用下一跳查询追加证据链）
            graph.addConditionalEdges(NODE_HOP_CHECK, edge_async(router.afterHopCheck()),
                    Map.of(Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_RETRIEVAL, NODE_RETRIEVAL,
                            Router.TO_HANDOFF, NODE_HANDOFF));
            // generate 生成后路由：LLM 异常（error 非空）→ fallback 兜底；正常 → validate 质量校验
            graph.addConditionalEdges(NODE_GENERATE, edge_async(router.afterGenerate()),
                    Map.of(Router.TO_VALIDATE, NODE_VALIDATE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // validate 校验路由（有限重试环）：通过 → finalize 收尾；
            //                 grounding（回答与资料矛盾）→ retrieval 回检索重查；
            //                 format 等其他失败未达重试上限 → generate 回生成重试；超限 → fallback 兜底
            graph.addConditionalEdges(NODE_VALIDATE, edge_async(router.afterValidate()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_RETRIEVAL, NODE_RETRIEVAL,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // fallback 直连 finalize：兜底降级答案汇入收尾（任何异常路径都保证有输出）
            graph.addEdge(NODE_FALLBACK, NODE_FINALIZE);
            // handoff 直连 finalize：转人工提示汇入收尾
            graph.addEdge(NODE_HANDOFF, NODE_FINALIZE);
            // finalize 直连 END：收尾组装非空结果后无条件结束
            graph.addEdge(NODE_FINALIZE, END);

            // 步骤4：编译图 —— 挂 checkpoint 持久化器 + 递归上限（防路由死循环）
            return graph.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .recursionLimit(recursionLimit())
                    .build());
        } catch (GraphStateException e) {
            // 步骤5：图装配失败是启动期配置错误，fail-fast 暴露问题
            throw new IllegalStateException("Agent graph assembly failed", e);
        }
    }

    /**
     * 递归上限：正常路径 + 多跳环上限 + 校验重试环上限 + 安全余量，防御路由死循环。
     * <p>
     * 推导：图上有两个环 ——
     * 多跳环 hop_check→retrieval 每次占 2 个节点，最多 maxHops 轮；
     * 质检环 validate→retrieval→generate 每次占 3 个节点，最多 maxRetries 轮；
     * 两者之和再加固定余量 10 覆盖 entry/intent/decompose/fallback/finalize 等线性节点开销。
     * 若路由配置错误导致死循环，达到上限后框架会抛异常中断，而非无限跑。
     */
    private int recursionLimit() {
        // 步骤1：读取配置的多跳与校验重试上限，至少为 1（防御配置为 0/负数）
        int maxHops = Math.max(1, properties.getMaxHops());
        int maxRetries = Math.max(1, properties.getMaxValidateRetries());
        // 步骤2：多跳环 2 节点 × 轮数 + 质检环 3 节点 × 轮数 + 10 个线性节点余量
        return maxHops * 2 + maxRetries * 3 + 10;
    }
}
