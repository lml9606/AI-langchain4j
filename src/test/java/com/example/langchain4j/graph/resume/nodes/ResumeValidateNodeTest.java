package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 校验节点单测：章节骨架、手机号/邮箱/日期锚点、变更清单格式。
 */
class ResumeValidateNodeTest {

    private static final String ORIGINAL = """
            ## 基本信息
            张三 13800138000 test@example.com
            ## 工作经历
            2020.01-2023.06 某公司 Java 工程师
            ## 项目经历
            订单系统重构
            """;

    private ResumeValidateNode node;

    @BeforeEach
    void setUp() {
        node = new ResumeValidateNode(new AgentProperties());
    }

    @Test
    @DisplayName("章节与事实锚点都保留 → valid=true")
    void validRevision() {
        String revised = """
                ## 基本信息
                张三 13800138000 test@example.com
                ## 工作经历
                2020.01-2023.06 某公司 Java 工程师，负责订单系统重构
                ## 项目经历
                订单系统重构：主导拆分与性能优化
                """;

        Map<String, Object> updates = node.apply(state(revised, validChanges()));

        assertThat(validation(updates).valid()).isTrue();
    }

    @Test
    @DisplayName("改稿丢失原文章节 → STRUCTURE，retryCount+1")
    void missingHeadingFails() {
        String revised = """
                ## 基本信息
                张三 13800138000 test@example.com
                ## 工作经历
                2020.01-2023.06 某公司 Java 工程师
                """;

        Map<String, Object> updates = node.apply(state(revised, validChanges()));

        assertThat(validation(updates).valid()).isFalse();
        assertThat(validation(updates).issue()).isEqualTo("STRUCTURE");
        assertThat(updates.get(ResumeOptimizeState.RETRY_COUNT)).isEqualTo(1);
    }

    @Test
    @DisplayName("改稿丢失原文手机号 → FACT")
    void missingPhoneFails() {
        String revised = """
                ## 基本信息
                张三 test@example.com
                ## 工作经历
                2020.01-2023.06 某公司 Java 工程师
                ## 项目经历
                订单系统重构
                """;

        Map<String, Object> updates = node.apply(state(revised, validChanges()));

        assertThat(validation(updates).issue()).isEqualTo("FACT");
        assertThat(validation(updates).reason()).contains("手机号");
    }

    @Test
    @DisplayName("改稿新增原文没有的邮箱 → FACT")
    void newEmailFails() {
        String revised = """
                ## 基本信息
                张三 13800138000 test@example.com new@example.com
                ## 工作经历
                2020.01-2023.06 某公司 Java 工程师
                ## 项目经历
                订单系统重构
                """;

        Map<String, Object> updates = node.apply(state(revised, validChanges()));

        assertThat(validation(updates).issue()).isEqualTo("FACT");
        assertThat(validation(updates).reason()).contains("新增").contains("邮箱");
    }

    @Test
    @DisplayName("改稿新增原文没有的日期 → FACT")
    void newDateFails() {
        String revised = """
                ## 基本信息
                张三 13800138000 test@example.com
                ## 工作经历
                2020.01-2024.01 某公司 Java 工程师
                ## 项目经历
                订单系统重构
                """;

        Map<String, Object> updates = node.apply(state(revised, validChanges()));

        assertThat(validation(updates).issue()).isEqualTo("FACT");
        assertThat(validation(updates).reason()).contains("日期");
    }

    @Test
    @DisplayName("变更类型非法 → FORMAT")
    void invalidChangeTypeFails() {
        String revised = """
                ## 基本信息
                张三 13800138000 test@example.com
                ## 工作经历
                2020.01-2023.06 某公司 Java 工程师
                ## 项目经历
                订单系统重构
                """;
        List<Map<String, Object>> changes = List.of(Map.of(
                "section", "工作经历",
                "type", "unknown",
                "summary", "改了什么",
                "reason", "为什么改"));

        Map<String, Object> updates = node.apply(state(revised, changes));

        assertThat(validation(updates).issue()).isEqualTo("FORMAT");
    }

    private ResumeOptimizeState state(String revised, List<Map<String, Object>> changes) {
        return new ResumeOptimizeState(Map.of(
                ResumeOptimizeState.RESUME_MD, ORIGINAL,
                ResumeOptimizeState.REVISED_MD, revised,
                ResumeOptimizeState.CHANGES, changes,
                ResumeOptimizeState.RETRY_COUNT, 0));
    }

    private List<Map<String, Object>> validChanges() {
        return List.of(Map.of(
                "section", "工作经历",
                "type", "rewrote",
                "summary", "精简描述",
                "reason", "对齐目标岗位"));
    }

    private ResumeOptimizeState.Validation validation(Map<String, Object> updates) {
        return (ResumeOptimizeState.Validation) updates.get(ResumeOptimizeState.VALIDATION);
    }
}
