package com.example.langchain4j.graph;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.edges.Router;
import com.example.langchain4j.graph.nodes.EntryNode;
import com.example.langchain4j.graph.nodes.FallbackNode;
import com.example.langchain4j.graph.nodes.FinalizeNode;
import com.example.langchain4j.graph.nodes.GenerateNode;
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
 * 图结构：START → entry(守卫) ⇢ intent(意图) → retrieval(检索) → generate(生成)
 *        → validate(校验) ⇄（有限重试环）→ fallback(兜底) → finalize(收尾) → END
 *        （entry 校验失败 → 直接 fallback，跳过 intent，省一次 LLM 调用）
 * <p>
 * 说明：
 * <ul>
 *   <li>checkpointSaver 由调用方（config 层）传入，Postgres 不可用时外部降级为 MemorySaver</li>
 *   <li>图装配失败属于启动期配置错误，直接 fail-fast 抛异常，不吞掉</li>
 *   <li>recursionLimit 基于重试上限动态计算，防御路由死循环</li>
 * </ul>
 */
@Component
public class AgentGraphBuilder {

    /** 节点名常量：路由映射与节点注册共用，避免字符串漂移 */
    public static final String NODE_ENTRY = "entry";
    public static final String NODE_INTENT = "intent";
    public static final String NODE_RETRIEVAL = "retrieval";
    public static final String NODE_GENERATE = "generate";
    public static final String NODE_VALIDATE = "validate";
    public static final String NODE_FALLBACK = "fallback";
    public static final String NODE_FINALIZE = "finalize";

    private final StateSerializer<WorkflowState> stateSerializer;
    private final Router router;
    private final EntryNode entryNode;
    private final IntentNode intentNode;
    private final RetrievalNode retrievalNode;
    private final GenerateNode generateNode;
    private final ValidateNode validateNode;
    private final FallbackNode fallbackNode;
    private final FinalizeNode finalizeNode;
    private final AgentProperties properties;

    public AgentGraphBuilder(WorkflowStateSerializer stateSerializer,
                             Router router,
                             EntryNode entryNode,
                             IntentNode intentNode,
                             RetrievalNode retrievalNode,
                             GenerateNode generateNode,
                             ValidateNode validateNode,
                             FallbackNode fallbackNode,
                             FinalizeNode finalizeNode,
                             AgentProperties properties) {
        this.stateSerializer = stateSerializer;
        this.router = router;
        this.entryNode = entryNode;
        this.intentNode = intentNode;
        this.retrievalNode = retrievalNode;
        this.generateNode = generateNode;
        this.validateNode = validateNode;
        this.fallbackNode = fallbackNode;
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
        StateGraph<WorkflowState> graph = new StateGraph<>(stateSerializer);
        try {
            graph.addNode(NODE_ENTRY, node_async(entryNode));
            graph.addNode(NODE_INTENT, node_async(intentNode));
            graph.addNode(NODE_RETRIEVAL, node_async(retrievalNode));
            graph.addNode(NODE_GENERATE, node_async(generateNode));
            graph.addNode(NODE_VALIDATE, node_async(validateNode));
            graph.addNode(NODE_FALLBACK, node_async(fallbackNode));
            graph.addNode(NODE_FINALIZE, node_async(finalizeNode));

            graph.addEdge(START, NODE_ENTRY);
            // 守卫路由：输入校验失败（空输入）直接兜底，跳过意图识别，省一次 LLM 调用
            graph.addConditionalEdges(NODE_ENTRY, edge_async(router.afterEntry()),
                    Map.of(Router.TO_INTENT, NODE_INTENT,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 意图路由：有效意图 → 检索；reject/异常 → 兜底
            graph.addConditionalEdges(NODE_INTENT, edge_async(router.afterIntent()),
                    Map.of(Router.TO_RETRIEVAL, NODE_RETRIEVAL,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            graph.addEdge(NODE_RETRIEVAL, NODE_GENERATE);
            // 生成后路由：LLM 异常 → 兜底；正常 → 校验
            graph.addConditionalEdges(NODE_GENERATE, edge_async(router.afterGenerate()),
                    Map.of(Router.TO_VALIDATE, NODE_VALIDATE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            // 校验路由：通过 → 收尾；未达重试上限 → 回生成；超限 → 兜底
            graph.addConditionalEdges(NODE_VALIDATE, edge_async(router.afterValidate()),
                    Map.of(Router.TO_FINALIZE, NODE_FINALIZE,
                            Router.TO_GENERATE, NODE_GENERATE,
                            Router.TO_FALLBACK, NODE_FALLBACK));
            graph.addEdge(NODE_FALLBACK, NODE_FINALIZE);
            graph.addEdge(NODE_FINALIZE, END);

            return graph.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .recursionLimit(recursionLimit())
                    .build());
        } catch (GraphStateException e) {
            // 图装配失败是启动期配置错误，fail-fast 暴露问题
            throw new IllegalStateException("Agent graph assembly failed", e);
        }
    }

    /**
     * 递归上限：正常路径 + 校验重试环上限 + 安全余量，防御路由死循环。
     * <p>
     * 推导：最坏情况下 validate→generate 环最多跑 maxRetries 次，
     * 每次环占 2 个节点（validate + generate），乘 2 后加固定余量 10
     * 覆盖 entry/intent/retrieval/fallback/finalize 等线性节点开销。
     * 若路由配置错误导致死循环，达到上限后框架会抛异常中断，而非无限跑。
     */
    private int recursionLimit() {
        int maxRetries = Math.max(1, properties.getMaxValidateRetries());
        return maxRetries * 2 + 10;
    }
}
