package com.example.langchain4j.graph.resume.nodes;

import com.example.langchain4j.config.AgentProperties;
import com.example.langchain4j.graph.resume.ResumeOptimizeState;
import com.example.langchain4j.graph.resume.model.ResumeTargetJob;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 目标画像节点单测：≤5 个岗位喂完整 JD，超过 5 个走聚合 + 代表岗位；自由模式校验章节。
 */
class ResumeTargetProfileNodeTest {

    private ResumeTargetProfileNode node;

    @BeforeEach
    void setUp() {
        AgentProperties properties = new AgentProperties();
        properties.getResume().setMaxTargetJobs(10);
        properties.getResume().setRepresentativeCount(5);
        properties.getResume().setMaxTargetJdChars(1200);
        node = new ResumeTargetProfileNode(properties);
    }

    @Test
    @DisplayName("1 个目标岗位 → 完整 JD 进入画像，不触发聚合")
    void oneJobUsesFullJd() {
        Map<String, Object> updates = node.apply(targetedState(List.of(job("Java 后端", "某公司", "负责订单系统"))));

        String block = (String) updates.get(ResumeOptimizeState.TARGET_BLOCK);
        assertThat(block).contains("### 目标岗位 1");
        assertThat(block).contains("Java 后端");
        assertThat(block).contains("负责订单系统");
        assertThat(updates).doesNotContainKey(ResumeOptimizeState.ERROR);
    }

    @Test
    @DisplayName("6 个目标岗位 → 聚合频次 + 5 个代表岗位")
    void manyJobsUsesAggregatedProfile() {
        List<ResumeTargetJob> jobs = new ArrayList<>();
        for (int i = 1; i <= 6; i++) {
            jobs.add(job("Java 后端 " + i, "公司 " + i, "JD 内容 " + i + "\n".repeat(i)));
        }

        Map<String, Object> updates = node.apply(targetedState(jobs));

        String block = (String) updates.get(ResumeOptimizeState.TARGET_BLOCK);
        assertThat(block).contains("目标岗位共 6 个");
        assertThat(block).contains("职位名频次");
        assertThat(block).contains("代表1 岗位");
        assertThat(block).contains("全部已选岗位一览");
        assertThat((List<?>) updates.get(ResumeOptimizeState.TARGET_JOBS)).hasSize(6);
    }

    @Test
    @DisplayName("自由模式的选定章节不存在 → 写 error，不进入生成")
    void freeModeRejectsUnknownSection() {
        Map<String, Object> updates = node.apply(new ResumeOptimizeState(Map.of(
                ResumeOptimizeState.MODE, "FREE",
                ResumeOptimizeState.RESUME_MD, "## 工作经历\nJava 开发",
                ResumeOptimizeState.SELECTED_SECTIONS, List.of("项目经历"))));

        assertThat((String) updates.get(ResumeOptimizeState.ERROR))
                .contains("选定章节不存在").contains("项目经历");
    }

    @Test
    @DisplayName("自由模式的选定章节存在 → 不写 error，目标块为空")
    void freeModeAcceptsExistingSection() {
        Map<String, Object> updates = node.apply(new ResumeOptimizeState(Map.of(
                ResumeOptimizeState.MODE, "FREE",
                ResumeOptimizeState.RESUME_MD, "## 工作经历\nJava 开发",
                ResumeOptimizeState.SELECTED_SECTIONS, List.of("工作经历"))));

        assertThat(updates).doesNotContainKey(ResumeOptimizeState.ERROR);
        assertThat(updates.get(ResumeOptimizeState.TARGET_BLOCK)).isEqualTo("");
    }

    private ResumeOptimizeState targetedState(List<ResumeTargetJob> jobs) {
        return new ResumeOptimizeState(Map.of(
                ResumeOptimizeState.MODE, "TARGETED",
                ResumeOptimizeState.RESUME_MD, "## 工作经历\nJava 开发",
                ResumeOptimizeState.TARGET_JOBS,
                jobs.stream().map(ResumeTargetJob::toMap).toList()));
    }

    private ResumeTargetJob job(String name, String company, String jd) {
        return new ResumeTargetJob(name, company, "25-40K", jd,
                List.of("Java", "Spring Boot"), List.of("后端"));
    }
}
