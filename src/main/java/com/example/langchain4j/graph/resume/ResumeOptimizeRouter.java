package com.example.langchain4j.graph.resume;

import com.example.langchain4j.config.AgentProperties;
import org.bsc.langgraph4j.action.EdgeAction;
import org.springframework.stereotype.Component;

/**
 * 简历优化条件路由：只读节点写好的显式标记，不在路由里重复计算校验逻辑。
 * <p>
 * 路由 key 与 {@link ResumeOptimizeGraphBuilder} 中的 mapping 一一对应。
 */
@Component
public class ResumeOptimizeRouter {

    /** entry 通过 → 目标画像 */
    public static final String TO_TARGET = "to_target";
    /** target/generate 通过 → 生成/校验 */
    public static final String TO_GENERATE = "to_generate";
    /** generate 通过 → 校验 */
    public static final String TO_VALIDATE = "to_validate";
    /** validate 通过 → diff */
    public static final String TO_DIFF = "to_diff";
    /** 成功收尾 → finalize */
    public static final String TO_FINALIZE = "to_finalize";
    /** 任意节点失败 / 校验重试超限 → 兜底 */
    public static final String TO_FALLBACK = "to_fallback";

    private final AgentProperties properties;

    public ResumeOptimizeRouter(AgentProperties properties) {
        this.properties = properties;
    }

    /** entry 后：清洗/模式校验失败 → 兜底；否则进入目标画像 */
    public EdgeAction<ResumeOptimizeState> afterEntry() {
        return state -> hasError(state) ? TO_FALLBACK : TO_TARGET;
    }

    /** target 后：章节/岗位校验失败 → 兜底；否则生成 */
    public EdgeAction<ResumeOptimizeState> afterTarget() {
        return state -> hasError(state) ? TO_FALLBACK : TO_GENERATE;
    }

    /** generate 后：LLM/解析失败 → 兜底；否则校验 */
    public EdgeAction<ResumeOptimizeState> afterGenerate() {
        return state -> hasError(state) ? TO_FALLBACK : TO_VALIDATE;
    }

    /**
     * validate 后：通过 → diff；失败且未达重试上限 → 带原因重写；
     * 达到上限 → 兜底（不返回半份简历）。
     */
    public EdgeAction<ResumeOptimizeState> afterValidate() {
        return state -> {
            if (state.validation().valid()) {
                return TO_DIFF;
            }
            int maxRetries = Math.max(1, properties.getResume().getMaxRetries());
            if (state.retryCount() < maxRetries) {
                return TO_GENERATE;
            }
            return TO_FALLBACK;
        };
    }

    private boolean hasError(ResumeOptimizeState state) {
        return state.error() != null && !state.error().isBlank();
    }
}
