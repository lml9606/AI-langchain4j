package com.example.langchain4j.graph.common.nodes;

import com.example.langchain4j.graph.state.WorkflowState;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Pattern;

/**
 * 收尾节点：结果兜底组装（防空输出）并标记流程完成。
 * 持久化落库由 service 层完成，节点保持纯净。
 */
@Slf4j
@Component
public class FinalizeNode implements NodeAction<WorkflowState> {

    /** 模型偶尔仍会输出来源编号：统一在出口清洗，保证用户看到的正文干净。 */
    private static final Pattern REFERENCE_MARKER = Pattern.compile(
            "[【\\[]\\s*参考资料\\s*\\d*\\s*[】\\]]");

    /** 收尾节点：保证结果非空并标记流程完成。 */
    @Override
    public Map<String, Object> apply(WorkflowState state) {
        // 步骤1：读取生成结果
        String result = stripReferenceMarkers(state.result());
        // 步骤2：最后一道防线 —— 任何路径到达这里都保证 result 非空
        if (result == null || result.isBlank()) {
            log.warn("[finalize] empty result, apply last-resort answer");
            result = "抱歉，未能生成有效回答，请稍后重试。";
        }
        // 步骤3：记录收尾结果（是否兜底 + 结果长度），方便追踪出口状态
        log.info("[finalize] done, intent={}, fallback={}, handoff={}, retryCount={}, depth={}, confidence={}, resultLength={}, rootCause={}",
                state.intent(), state.fallback(), state.handoff(), state.retryCount(),
                state.retrievalDepth(), state.finalConfidence(), result.length(), state.rootCause());
        // 步骤4：返回增量更新：最终结果 + 流程完成标记
        return Map.of(
                WorkflowState.RESULT, result,
                WorkflowState.DONE, true);
    }

    /** 去掉【参考资料 N】/[参考资料 N] 标记，并清理标记移除后留下的多余空格与空行。 */
    private String stripReferenceMarkers(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String cleaned = REFERENCE_MARKER.matcher(text).replaceAll("");
        cleaned = cleaned.replaceAll("[ \\t]+([，。；：！？])", "$1");
        cleaned = cleaned.replaceAll("[ \\t]{2,}", " ");
        cleaned = cleaned.replaceAll("\\n{3,}", "\n\n");
        return cleaned.trim();
    }
}
