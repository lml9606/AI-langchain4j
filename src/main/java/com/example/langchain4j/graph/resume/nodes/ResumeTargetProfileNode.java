package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import com.example.langchain4j.graph.resume.model.ResumeOptimizeMode;
import com.example.langchain4j.graph.resume.model.ResumeTargetJob;
import com.example.langchain4j.graph.resume.util.ResumeSectionNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 目标画像节点：把目标岗位/选定章节装配成提示词文本块。
 * <p>
 * 全程不调 LLM：JD 聚合、频次统计、代表岗位筛选都是确定性计算，
 * 成本可预测，也便于单测覆盖。
 * <p>
 * 定向模式：≤ representativeCount 个岗位喂完整 JD；超过时聚合职位名/技能/标签频次，
 * 再挑 JD 最完整的代表岗位，避免提示词无限膨胀。
 * 自由模式：校验用户选定章节确实存在，再交给生成节点按章节优化。
 */
@Slf4j
@Component
public class ResumeTargetProfileNode implements NodeAction<ResumeOptimizeState> {

    private final AgentProperties properties;

    public ResumeTargetProfileNode(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(ResumeOptimizeState state) {
        Map<String, Object> updates = new HashMap<>();
        ResumeOptimizeMode mode = ResumeOptimizeMode.from(state.mode());
        if (mode == null) {
            updates.put(ResumeOptimizeState.ERROR, "不支持的优化模式：" + state.mode());
            return updates;
        }

        if (mode == ResumeOptimizeMode.FREE) {
            return buildFree(updates, state);
        }
        return buildTargeted(updates, state);
    }

    private Map<String, Object> buildFree(Map<String, Object> updates, ResumeOptimizeState state) {
        List<String> selected = state.selectedSections();
        if (!selected.isEmpty()) {
            Set<String> available = ResumeSectionNormalizer.headingSet(state.resumeMd());
            List<String> missing = selected.stream()
                    .filter(section -> !available.contains(ResumeSectionNormalizer.normalizeTitle(section)))
                    .toList();
            if (!missing.isEmpty()) {
                updates.put(ResumeOptimizeState.ERROR,
                        "选定章节不存在：" + String.join("、", missing)
                                + "；可选章节：" + String.join("、",
                                ResumeSectionNormalizer.extractSectionTitles(state.resumeMd())));
                return updates;
            }
        }
        updates.put(ResumeOptimizeState.TARGET_BLOCK, "");
        log.info("[resume-target] mode=FREE, selectedSections={}", selected.size());
        return updates;
    }

    private Map<String, Object> buildTargeted(Map<String, Object> updates, ResumeOptimizeState state) {
        List<ResumeTargetJob> jobs = state.targetJobs().stream()
                .map(ResumeTargetJob::fromMap)
                .toList();
        if (jobs.isEmpty()) {
            updates.put(ResumeOptimizeState.ERROR, "定向优化至少需要 1 个目标岗位");
            return updates;
        }
        int maxJobs = Math.max(1, properties.getResume().getMaxTargetJobs());
        List<ResumeTargetJob> bounded = jobs.stream().limit(maxJobs).toList();
        String block = buildBlock(bounded);
        updates.put(ResumeOptimizeState.TARGET_BLOCK, block);
        updates.put(ResumeOptimizeState.TARGET_JOBS,
                bounded.stream().map(ResumeTargetJob::toMap).toList());
        log.info("[resume-target] mode=TARGETED, inputJobs={}, usedJobs={}, blockChars={}",
                jobs.size(), bounded.size(), block.length());
        return updates;
    }

    private String buildBlock(List<ResumeTargetJob> jobs) {
        int representative = Math.max(1, properties.getResume().getRepresentativeCount());
        int jdLimit = Math.max(200, properties.getResume().getMaxTargetJdChars());
        if (jobs.size() <= representative) {
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < jobs.size(); i++) {
                ResumeTargetJob job = jobs.get(i);
                parts.add("### 目标岗位 " + (i + 1) + "\n"
                        + "- 岗位：" + job.jobName() + "  公司：" + job.company()
                        + "  薪资：" + job.salary() + "\n"
                        + "- JD：\n" + jdExcerpt(job, jdLimit));
            }
            return String.join("\n\n", parts);
        }

        Map<String, Integer> titles = new LinkedHashMap<>();
        Map<String, Integer> skills = new LinkedHashMap<>();
        Map<String, Integer> labels = new LinkedHashMap<>();
        for (ResumeTargetJob job : jobs) {
            count(titles, job.jobName());
            job.skills().forEach(skill -> count(skills, skill));
            job.labels().forEach(label -> count(labels, label));
        }

        List<ResumeTargetJob> representatives = jobs.stream()
                .sorted(Comparator.comparingInt((ResumeTargetJob job) -> job.jd().length()).reversed())
                .limit(representative)
                .toList();

        List<String> lines = new ArrayList<>();
        lines.add("目标岗位共 " + jobs.size() + " 个。");
        lines.add("职位名频次：" + top(titles, 10));
        lines.add("技能要求频次：" + top(skills, 40));
        lines.add("标签频次：" + top(labels, 20));
        lines.add("");
        lines.add("本批中 JD 最完整的代表岗位：");
        for (int i = 0; i < representatives.size(); i++) {
            ResumeTargetJob job = representatives.get(i);
            lines.add("- 代表" + (i + 1) + " 岗位：" + job.jobName()
                    + "  公司：" + job.company() + "  薪资：" + job.salary());
            lines.add(jdExcerpt(job, jdLimit));
        }
        lines.add("");
        lines.add("全部已选岗位一览（职位名 + 公司）：");
        for (ResumeTargetJob job : jobs) {
            lines.add("- " + job.jobName() + "  公司：" + job.company());
        }
        return String.join("\n", lines);
    }

    private String jdExcerpt(ResumeTargetJob job, int limit) {
        String jd = job.jd() == null ? "" : job.jd().trim();
        if (!jd.isEmpty()) {
            return truncate(jd, limit);
        }
        List<String> bits = new ArrayList<>();
        bits.addAll(job.skills());
        bits.addAll(job.labels());
        if (!bits.isEmpty()) {
            return "（无 JD）标签/技能：" + String.join("、",
                    bits.subList(0, Math.min(12, bits.size())));
        }
        return "（无 JD，只按职位名 / 公司参与）";
    }

    private String truncate(String text, int limit) {
        if (text.length() <= limit) {
            return text;
        }
        return text.substring(0, limit) + "\n…（已截断）";
    }

    private void count(Map<String, Integer> counter, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        counter.merge(value.trim(), 1, Integer::sum);
    }

    private String top(Map<String, Integer> counter, int limit) {
        if (counter.isEmpty()) {
            return "（无）";
        }
        return counter.entrySet().stream()
                .sorted(Map.Entry.<String, Integer>comparingByValue().reversed()
                        .thenComparing(Map.Entry.comparingByKey()))
                .limit(limit)
                .map(entry -> entry.getKey() + "×" + entry.getValue())
                .reduce((a, b) -> a + ", " + b)
                .orElse("（无）");
    }
}
