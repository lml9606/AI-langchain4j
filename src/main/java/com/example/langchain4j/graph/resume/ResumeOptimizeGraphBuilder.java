package com.example.langchain4j.graph.resume;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.resume.nodes.ResumeDiffNode;
import com.example.langchain4j.graph.resume.nodes.ResumeEntryNode;
import com.example.langchain4j.graph.resume.nodes.ResumeFallbackNode;
import com.example.langchain4j.graph.resume.nodes.ResumeFinalizeNode;
import com.example.langchain4j.graph.resume.nodes.ResumeGenerateNode;
import com.example.langchain4j.graph.resume.nodes.ResumeTargetProfileNode;
import com.example.langchain4j.graph.resume.nodes.ResumeValidateNode;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.CompileConfig;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.bsc.langgraph4j.checkpoint.BaseCheckpointSaver;
import org.springframework.stereotype.Component;

import java.util.Map;

import static org.bsc.langgraph4j.GraphDefinition.END;
import static org.bsc.langgraph4j.GraphDefinition.START;
import static org.bsc.langgraph4j.action.AsyncEdgeAction.edge_async;
import static org.bsc.langgraph4j.action.AsyncNodeAction.node_async;

/**
 * 简历优化图装配器：节点注册 + 边连接 + 条件路由 + 编译。
 * <p>
 * 图结构：
 * <pre>
 * START → resume_entry → resume_target → resume_generate ⇄ resume_validate
 *                                                    （校验失败带原因重写，有限环）
 * resume_validate --valid--> resume_diff → resume_finalize → END
 * 任意节点 error / 校验重试超限 → resume_fallback → resume_finalize → END
 * </pre>
 * 说明：
 * <ul>
 *   <li>简历优化是一次请求内跑完的单次工作流，checkpoint 默认用 MemorySaver；
 *       真正需要持久化的是最终改稿（ai_resume_revision 表），不是图执行中间态；</li>
 *   <li>图装配失败属于启动期配置错误，fail-fast 抛异常；</li>
 *   <li>recursionLimit 按校验重试上限动态计算，防御路由配错导致死循环。</li>
 * </ul>
 */
@Slf4j
@Component
public class ResumeOptimizeGraphBuilder {

    public static final String NODE_ENTRY = "resume_entry";
    public static final String NODE_TARGET = "resume_target";
    public static final String NODE_GENERATE = "resume_generate";
    public static final String NODE_VALIDATE = "resume_validate";
    public static final String NODE_DIFF = "resume_diff";
    public static final String NODE_FINALIZE = "resume_finalize";
    public static final String NODE_FALLBACK = "resume_fallback";

    private final ResumeOptimizeStateSerializer stateSerializer;
    private final ResumeOptimizeRouter router;
    private final ResumeEntryNode entryNode;
    private final ResumeTargetProfileNode targetNode;
    private final ResumeGenerateNode generateNode;
    private final ResumeValidateNode validateNode;
    private final ResumeDiffNode diffNode;
    private final ResumeFinalizeNode finalizeNode;
    private final ResumeFallbackNode fallbackNode;
    private final AgentProperties properties;

    public ResumeOptimizeGraphBuilder(ResumeOptimizeStateSerializer stateSerializer,
                                      ResumeOptimizeRouter router,
                                      ResumeEntryNode entryNode,
                                      ResumeTargetProfileNode targetNode,
                                      ResumeGenerateNode generateNode,
                                      ResumeValidateNode validateNode,
                                      ResumeDiffNode diffNode,
                                      ResumeFinalizeNode finalizeNode,
                                      ResumeFallbackNode fallbackNode,
                                      AgentProperties properties) {
        this.stateSerializer = stateSerializer;
        this.router = router;
        this.entryNode = entryNode;
        this.targetNode = targetNode;
        this.generateNode = generateNode;
        this.validateNode = validateNode;
        this.diffNode = diffNode;
        this.finalizeNode = finalizeNode;
        this.fallbackNode = fallbackNode;
        this.properties = properties;
    }

    /**
     * 组装并编译图。
     *
     * @param checkpointSaver checkpoint 持久化器（v1 使用 MemorySaver）
     * @return 编译后的可执行图
     */
    public CompiledGraph<ResumeOptimizeState> build(BaseCheckpointSaver checkpointSaver) {
        StateGraph<ResumeOptimizeState> graph = new StateGraph<>(stateSerializer);
        try {
            graph.addNode(NODE_ENTRY, node_async(entryNode));
            graph.addNode(NODE_TARGET, node_async(targetNode));
            graph.addNode(NODE_GENERATE, node_async(generateNode));
            graph.addNode(NODE_VALIDATE, node_async(validateNode));
            graph.addNode(NODE_DIFF, node_async(diffNode));
            graph.addNode(NODE_FINALIZE, node_async(finalizeNode));
            graph.addNode(NODE_FALLBACK, node_async(fallbackNode));

            graph.addEdge(START, NODE_ENTRY);
            graph.addConditionalEdges(NODE_ENTRY, edge_async(router.afterEntry()),
                    Map.of(ResumeOptimizeRouter.TO_TARGET, NODE_TARGET,
                            ResumeOptimizeRouter.TO_FALLBACK, NODE_FALLBACK));
            graph.addConditionalEdges(NODE_TARGET, edge_async(router.afterTarget()),
                    Map.of(ResumeOptimizeRouter.TO_GENERATE, NODE_GENERATE,
                            ResumeOptimizeRouter.TO_FALLBACK, NODE_FALLBACK));
            graph.addConditionalEdges(NODE_GENERATE, edge_async(router.afterGenerate()),
                    Map.of(ResumeOptimizeRouter.TO_VALIDATE, NODE_VALIDATE,
                            ResumeOptimizeRouter.TO_FALLBACK, NODE_FALLBACK));
            graph.addConditionalEdges(NODE_VALIDATE, edge_async(router.afterValidate()),
                    Map.of(ResumeOptimizeRouter.TO_DIFF, NODE_DIFF,
                            ResumeOptimizeRouter.TO_GENERATE, NODE_GENERATE,
                            ResumeOptimizeRouter.TO_FALLBACK, NODE_FALLBACK));
            graph.addEdge(NODE_DIFF, NODE_FINALIZE);
            graph.addEdge(NODE_FALLBACK, NODE_FINALIZE);
            graph.addEdge(NODE_FINALIZE, END);

            CompiledGraph<ResumeOptimizeState> compiled = graph.compile(CompileConfig.builder()
                    .checkpointSaver(checkpointSaver)
                    .recursionLimit(recursionLimit())
                    .build());
            log.info("[resume-graph] compiled, nodes=7, recursionLimit={}", recursionLimit());
            return compiled;
        } catch (GraphStateException e) {
            throw new IllegalStateException("Resume optimize graph assembly failed", e);
        }
    }

    /**
     * 递归上限：校验重试环每次占 generate + validate 两个节点，
     * 再加上 entry/target/diff/finalize/fallback 等线性节点与安全余量。
     */
    private int recursionLimit() {
        int maxRetries = Math.max(1, properties.getResume().getMaxRetries());
        return maxRetries * 2 + 8;
    }
}
