package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import com.example.langchain4j.graph.resume.model.ResumeOptimizeMode;
import com.example.langchain4j.graph.resume.util.ResumeSectionNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历优化入口守卫：清洗、截断、章节规范化、计数归零。
 * <p>
 * 防御策略：空输入/非法模式直接写 error，由路由转兜底，不进入 LLM 调用；
 * 控制字符清洗与长度截断在入口一次完成，后续节点只消费 {@code resumeMd}。
 */
@Slf4j
@Component
public class ResumeEntryNode implements NodeAction<ResumeOptimizeState> {

    private final AgentProperties properties;

    public ResumeEntryNode(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(ResumeOptimizeState state) {
        Map<String, Object> updates = new HashMap<>();
        // 每轮入口归零：断点恢复/重试时不带上一轮残留
        updates.put(ResumeOptimizeState.RETRY_COUNT, 0);
        updates.put(ResumeOptimizeState.ERROR, "");
        updates.put(ResumeOptimizeState.FALLBACK, false);
        updates.put(ResumeOptimizeState.DONE, false);
        updates.put(ResumeOptimizeState.TRUNCATED, false);
        updates.put(ResumeOptimizeState.REVISED_MD, "");
        updates.put(ResumeOptimizeState.CHANGES, List.of());
        updates.put(ResumeOptimizeState.DIFF, List.of());

        String raw = state.resumeRaw();
        String cleaned = raw == null ? "" : raw.replaceAll("[\\p{Cntrl}]", " ").trim();
        if (cleaned.isEmpty()) {
            updates.put(ResumeOptimizeState.ERROR, "简历原文为空，请先上传或粘贴简历");
            return updates;
        }

        ResumeOptimizeMode mode = ResumeOptimizeMode.from(state.mode());
        if (mode == null) {
            updates.put(ResumeOptimizeState.ERROR, "不支持的优化模式：" + state.mode());
            return updates;
        }
        updates.put(ResumeOptimizeState.MODE, mode.name());

        int maxChars = Math.max(1_000, properties.getResume().getMaxInputChars());
        boolean truncated = cleaned.length() > maxChars;
        String bounded = truncated ? cleaned.substring(0, maxChars) : cleaned;
        String markdown = ResumeSectionNormalizer.toMarkdown(bounded);
        if (markdown.isBlank()) {
            updates.put(ResumeOptimizeState.ERROR, "简历原文清洗后为空，请检查后重试");
            return updates;
        }

        updates.put(ResumeOptimizeState.RESUME_MD, markdown);
        updates.put(ResumeOptimizeState.INPUT_TRUNCATED, truncated);
        log.info("[resume-entry] mode={}, inputChars={}, truncated={}, sections={}",
                mode, cleaned.length(), truncated,
                ResumeSectionNormalizer.extractSectionTitles(markdown).size());
        return updates;
    }
}
