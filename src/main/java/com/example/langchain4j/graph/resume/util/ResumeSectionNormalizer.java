package com.example.langchain4j.graph.resume.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 简历章节规范化：把任意格式的简历原文提升为可供章节选择/校验的 Markdown。
 * <p>
 * 只做确定性处理，不调 LLM：
 * <ul>
 *   <li>原文已是 Markdown（存在 #{1,6} 标题）→ 原样返回；</li>
 *   <li>否则把独占一行的常见章节名（中文 / 英文）提升为 ## 标题，其余行一字不动；</li>
 *   <li>章节名匹配前先去掉「一、」「1.」「-」等行首编号/符号。</li>
 * </ul>
 * 该文本只用于分析（目标画像 / 生成 / 校验），不用于展示原文。
 */
public final class ResumeSectionNormalizer {

    /** 任意 Markdown 标题探测：只要原文已有标题体系就不再改写 */
    private static final Pattern MD_HEADING_ANY = Pattern.compile("^#{1,6}\\s+\\S", Pattern.MULTILINE);
    /** Markdown 标题提取 */
    private static final Pattern HEADING_PATTERN = Pattern.compile("^(#{1,6})\\s+(.+)$", Pattern.MULTILINE);
    /** 行首编号/符号：「一、」「1.」「(1)」「-」「•」等 */
    private static final Pattern LINE_PREFIX = Pattern.compile(
            "^\\s*(?:[一二三四五六七八九十]+、|\\(?[0-9A-Za-z]{1,2}[.、)]\\s*|[\\-–—*·•●◆■◇]+\\s*)");
    /** 标题比较时忽略的噪声字符 */
    private static final Pattern TITLE_NOISE = Pattern.compile("[\\s：:、。.．;；·•●◆■◇\\-–—]");

    private static final Set<String> KNOWN_SECTIONS = buildKnownSections();

    private ResumeSectionNormalizer() {
    }

    /**
     * 任意格式简历原文 → 供分析用的 Markdown 规范化文本。
     * 已含 Markdown 标题的原文原样返回；否则只提升独占一行的常见章节名。
     */
    public static String toMarkdown(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        if (MD_HEADING_ANY.matcher(normalized).find()) {
            return normalized;
        }
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length() + 32);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                out.append('\n');
            }
            String core = sectionCore(lines[i]);
            if (core == null) {
                out.append(lines[i]);
            } else {
                out.append("## ").append(core);
            }
        }
        return out.toString();
    }

    /**
     * 提取章节标题（去重、保持出现顺序）。
     * 非 Markdown 简历先经 {@link #toMarkdown} 规范化，因此纯文本里的
     * 「工作经历 / Education」同样能被识别。
     */
    public static List<String> extractSectionTitles(String text) {
        String markdown = toMarkdown(text);
        if (markdown.isBlank()) {
            return List.of();
        }
        List<String> titles = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        Matcher matcher = HEADING_PATTERN.matcher(markdown);
        while (matcher.find()) {
            String title = matcher.group(2).trim();
            String normalized = normalizeTitle(title);
            if (!normalized.isEmpty() && seen.add(normalized)) {
                titles.add(title);
            }
        }
        return titles;
    }

    /** 章节标题集合（归一化后），用于校验改稿是否保留原文章节骨架 */
    public static Set<String> headingSet(String text) {
        return new LinkedHashSet<>(extractSectionTitles(text).stream()
                .map(ResumeSectionNormalizer::normalizeTitle)
                .filter(s -> !s.isEmpty())
                .toList());
    }

    /** 标题归一化：去空白与常见标点、转小写，仅用于比较，不用于展示 */
    public static String normalizeTitle(String title) {
        if (title == null) {
            return "";
        }
        return TITLE_NOISE.matcher(title).replaceAll("").toLowerCase(Locale.ROOT);
    }

    /** 独占一行且命中常见章节名 → 返回干净标题；否则 null */
    private static String sectionCore(String line) {
        String s = line == null ? "" : line.trim();
        if (s.isEmpty() || s.length() > 30 || s.indexOf('\t') >= 0) {
            return null;
        }
        s = LINE_PREFIX.matcher(s).replaceFirst("").trim();
        s = s.replaceAll("^[：:、。.．;；·•●◆■◇\\s]+", "")
                .replaceAll("[：:、。.．;；·•●◆■◇\\s]+$", "");
        if (s.isEmpty() || s.length() > 30) {
            return null;
        }
        return KNOWN_SECTIONS.contains(s.toLowerCase(Locale.ROOT)) ? s : null;
    }

    private static Set<String> buildKnownSections() {
        List<String> names = List.of(
                // 中文常见章节
                "基本信息", "个人信息", "联系方式", "求职意向", "求职状态", "职业目标",
                "教育背景", "教育经历", "教育情况",
                "工作经历", "工作经验", "工作简介", "实习经历", "实习经验", "实践经历",
                "项目经历", "项目经验", "主要项目", "代表作", "作品集",
                "校园经历", "社会实践", "培训经历", "主要经历",
                "专业技能", "职业技能", "技能特长", "技能", "语言能力", "it技能",
                "自我评价", "个人评价", "自我介绍", "个人简介", "个人优势", "个人总结", "自我总结",
                "获奖经历", "所获荣誉", "荣誉奖项", "荣誉证书", "证书", "资格证书",
                "兴趣爱好", "其他", "其他说明", "附件",
                // 英文常见章节
                "summary", "professional summary", "objective", "profile", "personal statement",
                "experience", "work experience", "professional experience", "employment",
                "education", "academic background",
                "skills", "technical skills", "core skills",
                "projects", "selected projects",
                "awards", "honors", "certifications", "certificates",
                "activities", "interests", "references", "contact", "personal information");
        Set<String> set = new HashSet<>(names.size());
        for (String name : names) {
            set.add(name.toLowerCase(Locale.ROOT));
        }
        return Collections.unmodifiableSet(set);
    }
}
