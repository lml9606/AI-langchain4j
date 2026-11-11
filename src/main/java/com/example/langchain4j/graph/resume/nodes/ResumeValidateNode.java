package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import com.example.langchain4j.graph.resume.util.ResumeSectionNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.bsc.langgraph4j.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 改稿校验节点：确定性检查，不调 LLM。
 * <p>
 * 抓最危险的三类问题：
 * <ul>
 *   <li>格式：改稿为空、变更清单结构非法；</li>
 *   <li>结构：原文已有章节标题在改稿中丢失；</li>
 *   <li>事实：手机号/邮箱/日期锚点被改写，或改稿凭空新增了原文没有的联系方式/日期。</li>
 * </ul>
 * 语义级「是否编造了一段项目经历」不在这里判断，交给提示词硬约束 + diff 人工复核；
 * 后续若需要 LLM 质检，另加独立节点，不混入基础校验。
 */
@Slf4j
@Component
public class ResumeValidateNode implements NodeAction<ResumeOptimizeState> {

    private static final Pattern PHONE = Pattern.compile("(?<!\\d)(?:\\+?86[- ]?)?1[3-9]\\d{9}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern YEAR_MONTH = Pattern.compile(
            "((?:19|20)\\d{2})\\s*[.\\-/年]\\s*(0?[1-9]|1[0-2])(?:\\s*月)?");
    private static final Pattern YEAR_ONLY = Pattern.compile(
            "(?<!\\d)((?:19|20)\\d{2})(?!\\d)(?!\\s*[.\\-/年]\\s*(?:0?[1-9]|1[0-2]))");
    private static final Set<String> CHANGE_TYPES =
            Set.of("reordered", "trimmed", "expanded", "keyword", "rewrote");
    private static final int MAX_REASON_CHARS = 500;

    private final AgentProperties properties;

    public ResumeValidateNode(AgentProperties properties) {
        this.properties = properties;
    }

    @Override
    public Map<String, Object> apply(ResumeOptimizeState state) {
        String revised = state.revisedMd();
        if (revised == null || revised.isBlank()) {
            return invalid(state, ResumeOptimizeState.Validation.ISSUE_FORMAT, "修改稿为空");
        }

        Map<String, Object> formatResult = validateChanges(state);
        if (formatResult != null) {
            return formatResult;
        }

        Map<String, Object> structureResult = validateStructure(state, revised);
        if (structureResult != null) {
            return structureResult;
        }

        Map<String, Object> factResult = validateFacts(state, revised);
        if (factResult != null) {
            return factResult;
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(ResumeOptimizeState.VALIDATION, ResumeOptimizeState.Validation.of(true, "", ""));
        updates.put(ResumeOptimizeState.ERROR, "");
        log.info("[resume-validate] valid=true, changes={}, retry={}",
                state.changes().size(), state.retryCount());
        return updates;
    }

    /** 变更清单结构与类型校验；返回 null 表示通过 */
    private Map<String, Object> validateChanges(ResumeOptimizeState state) {
        List<Map<String, Object>> changes = state.changes();
        int maxChanges = Math.max(1, properties.getResume().getMaxChanges());
        if (changes.size() > maxChanges) {
            return invalid(state, ResumeOptimizeState.Validation.ISSUE_FORMAT,
                    "变更清单超过 " + maxChanges + " 条");
        }
        for (Map<String, Object> change : changes) {
            if (!change.containsKey("section") || !change.containsKey("type")
                    || !change.containsKey("summary") || !change.containsKey("reason")) {
                return invalid(state, ResumeOptimizeState.Validation.ISSUE_FORMAT,
                        "变更清单字段不完整，每条必须包含 section/type/summary/reason");
            }
            String type = String.valueOf(change.get("type"));
            if (!CHANGE_TYPES.contains(type)) {
                return invalid(state, ResumeOptimizeState.Validation.ISSUE_FORMAT,
                        "变更类型非法：" + type);
            }
        }
        return null;
    }

    /** 章节骨架校验：原文已有标题必须在改稿中保留；返回 null 表示通过 */
    private Map<String, Object> validateStructure(ResumeOptimizeState state, String revised) {
        List<String> originalTitles = ResumeSectionNormalizer.extractSectionTitles(state.resumeMd());
        if (originalTitles.isEmpty()) {
            return null;
        }
        Set<String> revisedHeadings = ResumeSectionNormalizer.headingSet(revised);
        List<String> missing = originalTitles.stream()
                .filter(title -> !revisedHeadings.contains(ResumeSectionNormalizer.normalizeTitle(title)))
                .toList();
        if (!missing.isEmpty()) {
            log.info("[resume-validate] structure broken, missingHeadings={}", missing.size());
            return invalid(state, ResumeOptimizeState.Validation.ISSUE_STRUCTURE,
                    "改稿丢失原文章节：" + String.join("、", missing)
                            + "。请保留原文章节标题体系，只改内容。");
        }
        return null;
    }

    /** 事实锚点校验：联系方式与日期必须保留原文值，且不得新增；返回 null 表示通过 */
    private Map<String, Object> validateFacts(ResumeOptimizeState state, String revised) {
        String original = state.resumeMd();
        List<String> problems = new ArrayList<>();

        Set<String> originalPhones = extractPhones(original);
        Set<String> revisedPhones = extractPhones(revised);
        List<String> missingPhones = difference(originalPhones, revisedPhones);
        List<String> newPhones = difference(revisedPhones, originalPhones);
        if (!missingPhones.isEmpty()) {
            problems.add("改稿未保留原文手机号：" + String.join("、", missingPhones));
        }
        if (!newPhones.isEmpty()) {
            problems.add("改稿新增了原文没有的手机号：" + String.join("、", newPhones));
        }

        Set<String> originalEmails = extractEmails(original);
        Set<String> revisedEmails = extractEmails(revised);
        List<String> missingEmails = difference(originalEmails, revisedEmails);
        List<String> newEmails = difference(revisedEmails, originalEmails);
        if (!missingEmails.isEmpty()) {
            problems.add("改稿未保留原文邮箱：" + String.join("、", missingEmails));
        }
        if (!newEmails.isEmpty()) {
            problems.add("改稿新增了原文没有的邮箱：" + String.join("、", newEmails));
        }

        Set<String> originalDates = extractDates(original);
        Set<String> revisedDates = extractDates(revised);
        List<String> missingDates = difference(originalDates, revisedDates);
        List<String> newDates = difference(revisedDates, originalDates);
        if (!missingDates.isEmpty()) {
            problems.add("改稿未保留原文日期：" + String.join("、", missingDates));
        }
        if (!newDates.isEmpty()) {
            problems.add("改稿新增了原文没有的日期：" + String.join("、", newDates));
        }

        if (problems.isEmpty()) {
            return null;
        }
        log.info("[resume-validate] fact anchors broken, problems={}, missingPhones={}, newPhones={}, missingEmails={}, newEmails={}, missingDates={}, newDates={}",
                problems.size(), missingPhones.size(), newPhones.size(),
                missingEmails.size(), newEmails.size(), missingDates.size(), newDates.size());
        return invalid(state, ResumeOptimizeState.Validation.ISSUE_FACT,
                String.join("；", problems) + "。事实字段必须逐字保留，不得新增或改写。");
    }

    private Map<String, Object> invalid(ResumeOptimizeState state, String issue, String reason) {
        Map<String, Object> updates = new HashMap<>();
        String bounded = reason == null ? "" : (reason.length() <= MAX_REASON_CHARS
                ? reason : reason.substring(0, MAX_REASON_CHARS));
        updates.put(ResumeOptimizeState.VALIDATION,
                ResumeOptimizeState.Validation.of(false, issue, bounded));
        updates.put(ResumeOptimizeState.RETRY_COUNT, state.retryCount() + 1);
        log.info("[resume-validate] valid=false, issue={}, retry={}", issue, state.retryCount() + 1);
        return updates;
    }

    private Set<String> extractPhones(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = PHONE.matcher(text == null ? "" : text);
        while (matcher.find()) {
            String digits = matcher.group().replaceAll("\\D", "");
            if (digits.length() == 13 && digits.startsWith("86")) {
                digits = digits.substring(2);
            }
            result.add(digits);
        }
        return result;
    }

    private Set<String> extractEmails(String text) {
        Set<String> result = new LinkedHashSet<>();
        Matcher matcher = EMAIL.matcher(text == null ? "" : text);
        while (matcher.find()) {
            result.add(matcher.group().toLowerCase(Locale.ROOT));
        }
        return result;
    }

    /**
     * 日期归一化为 YYYY-MM / YYYY：同一天的两种写法（2023.01 / 2023年1月）视为同一锚点；
     * 年-月优先，年-only 用负向前瞻避开重复统计。
     */
    private Set<String> extractDates(String text) {
        String source = text == null ? "" : text;
        Set<String> result = new LinkedHashSet<>();
        Matcher yearMonth = YEAR_MONTH.matcher(source);
        while (yearMonth.find()) {
            int month = Integer.parseInt(yearMonth.group(2));
            result.add(yearMonth.group(1) + "-" + String.format("%02d", month));
        }
        Matcher yearOnly = YEAR_ONLY.matcher(source);
        while (yearOnly.find()) {
            result.add(yearOnly.group(1));
        }
        return result;
    }

    private List<String> difference(Set<String> left, Set<String> right) {
        return left.stream().filter(item -> !right.contains(item)).toList();
    }
}
