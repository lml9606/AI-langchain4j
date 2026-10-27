package com.example.langchain4j.graph.edges;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.nodes.IntentNode;
import com.example.langchain4j.graph.state.WorkflowState;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * 条件路由：决定节点执行后的下一跳。
 * 路由 key 与 {@link com.example.langchain4j.graph.AgentGraphBuilder} 中的 mapping 对应。
 */
@Component
public class Router {

    public static final String TO_RETRIEVAL = "to_retrieval";
    public static final String TO_GENERATE = "to_generate";
    public static final String TO_VALIDATE = "to_validate";
    public static final String TO_FALLBACK = "to_fallback";
    public static final String TO_FINALIZE = "to_finalize";

    private final AgentProperties properties;

    public Router(AgentProperties properties) {
        this.properties = properties;
    }

    /** 意图节点后：有效意图走检索，reject/异常走兜底 */
    public EdgeAction<WorkflowState> afterIntent() {
        return state -> {
            if (state.error() != null && !state.error().isBlank()) {
                return TO_FALLBACK;
            }
            String intent = state.intent();
            if (IntentNode.INTENT_CODE_GENERATE.equals(intent)
                    || IntentNode.INTENT_KNOWLEDGE_QA.equals(intent)) {
                return TO_RETRIEVAL;
            }
            // reject 或未知意图
            return TO_FALLBACK;
        };
    }

    /** 生成节点后：异常走兜底，正常走校验 */
    public EdgeAction<WorkflowState> afterGenerate() {
        return state -> {
            if (state.error() != null && !state.error().isBlank()) {
                return TO_FALLBACK;
            }
            return TO_VALIDATE;
        };
    }

    /** 校验节点后：通过→收尾；失败且未达上限→重试生成；超限→兜底 */
    public EdgeAction<WorkflowState> afterValidate() {
        return state -> {
            // 判定结果由 ValidateNode 写入显式标记，路由只读标记避免逻辑重复
            if (state.validated()) {
                return TO_FINALIZE;
            }
            int maxRetries = Math.max(1, properties.getMaxValidateRetries());
            if (state.retryCount() < maxRetries) {
                return TO_GENERATE;
            }
            return TO_FALLBACK;
        };
    }
}
