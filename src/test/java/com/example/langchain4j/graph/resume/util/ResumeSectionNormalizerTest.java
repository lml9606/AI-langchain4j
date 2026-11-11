package com.example.langchain4j.graph.resume.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 章节规范化单测：纯文本章节提升、Markdown 原样放行、中英文标题识别。
 */
class ResumeSectionNormalizerTest {

    @Test
    @DisplayName("纯文本中文章节名 → 提升为 ## 标题，其余行不动")
    void promoteChineseSections() {
        String text = """
                张三
                13800138000
                基本信息
                男 / 北京
                工作经历
                2020.01-2023.06 某公司 Java 工程师
                项目经历
                订单系统重构
                """;

        String markdown = ResumeSectionNormalizer.toMarkdown(text);

        assertThat(markdown).contains("## 基本信息");
        assertThat(markdown).contains("## 工作经历");
        assertThat(markdown).contains("## 项目经历");
        assertThat(markdown).contains("13800138000");
        assertThat(ResumeSectionNormalizer.extractSectionTitles(text))
                .containsExactly("基本信息", "工作经历", "项目经历");
    }

    @Test
    @DisplayName("已有 Markdown 标题 → 原样返回")
    void keepMarkdownAsIs() {
        String text = "# 张三\n\n## 工作经历\nJava 开发\n";
        assertThat(ResumeSectionNormalizer.toMarkdown(text)).isEqualTo(text);
    }

    @Test
    @DisplayName("英文常见章节名 + 行首编号 → 能识别")
    void recognizeEnglishAndStripPrefixes() {
        String text = """
                Education
                B.S. Computer Science
                1. Work Experience
                Java Developer
                二、Skills
                Java, Spring
                """;

        List<String> titles = ResumeSectionNormalizer.extractSectionTitles(text);

        assertThat(titles).contains("Education", "Work Experience", "Skills");
    }

    @Test
    @DisplayName("标题归一化忽略空格、标点与大小写")
    void normalizeTitle() {
        assertThat(ResumeSectionNormalizer.normalizeTitle("工作经历："))
                .isEqualTo(ResumeSectionNormalizer.normalizeTitle(" 工作经历 "));
        assertThat(ResumeSectionNormalizer.normalizeTitle("Work Experience"))
                .isEqualTo("workexperience");
    }
}
