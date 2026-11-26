package com.example.langchain4j.eval;

import com.example.langchain4j.common.util.JsonUtil;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

/** 检索评测集完整性测试：只校验测试资源，不暴露线上接口，也不依赖数据库。 */
class GoldenRetrievalSetTest {

    private static final String GOLDEN_RESOURCE = "eval/golden-retrieval.jsonl";

    /** 测评集必须有足够样本、ID 唯一、字段完整，避免后续回归时样本悄悄失效。 */
    @Test
    void goldenSetIsLargeEnoughAndWellFormed() throws IOException {
        List<GoldenCase> cases = loadCases();

        assertThat(cases).hasSizeGreaterThanOrEqualTo(20);
        assertThat(cases).extracting(GoldenCase::id).doesNotHaveDuplicates();
        assertThat(cases).allSatisfy(evalCase -> {
            assertThat(evalCase.id()).isNotBlank();
            assertThat(evalCase.category()).isNotBlank();
            assertThat(evalCase.query()).isNotBlank();
            assertThat(evalCase.expectedKeywords()).isNotEmpty();
            assertThat(evalCase.expectedKeywords()).doesNotContainNull();
            assertThat(evalCase.topK()).isBetween(1, 10);
        });
    }

    /** 测评集应覆盖多个知识域，避免全部样本集中在单一主题导致指标失真。 */
    @Test
    void goldenSetCoversMultipleKnowledgeCategories() throws IOException {
        List<GoldenCase> cases = loadCases();

        assertThat(cases.stream().map(GoldenCase::category).distinct().count())
                .isGreaterThanOrEqualTo(8);
    }

    /** 逐行读取 JSONL，解析失败时带行号报错，方便维护样本时快速定位。 */
    private List<GoldenCase> loadCases() throws IOException {
        InputStream input = Objects.requireNonNull(
                getClass().getClassLoader().getResourceAsStream(GOLDEN_RESOURCE),
                "缺少测试资源: " + GOLDEN_RESOURCE);
        List<GoldenCase> cases = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String line;
            int lineNo = 0;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                if (line.isBlank() || line.trim().startsWith("#")) {
                    continue;
                }
                try {
                    cases.add(JsonUtil.fromJson(line, GoldenCase.class));
                } catch (RuntimeException e) {
                    throw new IllegalStateException("测评集第 " + lineNo + " 行解析失败", e);
                }
            }
        }
        return cases;
    }

    /** 测评样本：查询、期望关键词和召回窗口都与真实评测口径保持一致。 */
    private record GoldenCase(String id, String category, String query,
                              List<String> expectedKeywords, Integer topK) {
    }
}
