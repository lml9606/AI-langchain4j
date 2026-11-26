package com.example.langchain4j.eval;

import com.example.langchain4j.common.util.JsonUtil;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 检索评测样本：一条 query 对应一组期望关键词和召回窗口。
 *
 * 该 record 同时被出题、执行、验收三个阶段复用，字段与
 * src/test/resources/eval/golden-retrieval.jsonl 保持一致。
 */
public record EvalCase(String id,
                       String category,
                       String query,
                       List<String> expectedKeywords,
                       Integer topK) {

    /** 召回窗口兜底：未配置或非法值统一按 5 处理，避免执行器拿到 0。 */
    public int effectiveTopK() {
        return topK == null || topK < 1 ? 5 : topK;
    }

    /**
     * 从 JSONL 文件加载样本。
     *
     * 空行和以 # 开头的注释行跳过；单行解析失败时带行号抛出，方便排查坏样本。
     */
    public static List<EvalCase> load(Path path) throws IOException {
        Objects.requireNonNull(path, "path");
        if (!Files.exists(path)) {
            throw new IllegalArgumentException("评测集不存在: " + path);
        }
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
            return readLines(reader);
        }
    }

    /** 从 classpath 加载内置评测集；资源缺失直接失败，避免空跑得到假通过。 */
    public static List<EvalCase> loadClasspath(String resource) throws IOException {
        InputStream input = EvalCase.class.getClassLoader().getResourceAsStream(resource);
        if (input == null) {
            throw new IllegalArgumentException("缺少评测资源: " + resource);
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(input, StandardCharsets.UTF_8))) {
            return readLines(reader);
        }
    }

    /**
     * 校验样本完整性：ID/query 唯一、字段非空、关键词非空、topK 在 1~10。
     * 出题阶段先跑这一层，坏样本不进入执行器。
     */
    public static void validate(List<EvalCase> cases) {
        if (cases == null || cases.isEmpty()) {
            throw new IllegalArgumentException("评测集为空");
        }
        Set<String> ids = new HashSet<>();
        Set<String> queries = new HashSet<>();
        for (EvalCase evalCase : cases) {
            if (evalCase.id() == null || evalCase.id().isBlank()) {
                throw new IllegalArgumentException("存在空 id 的样本");
            }
            if (!ids.add(evalCase.id())) {
                throw new IllegalArgumentException("重复 id: " + evalCase.id());
            }
            if (evalCase.query() == null || evalCase.query().isBlank()) {
                throw new IllegalArgumentException("样本 " + evalCase.id() + " 的 query 为空");
            }
            if (!queries.add(evalCase.query().trim())) {
                throw new IllegalArgumentException("重复 query: " + evalCase.query());
            }
            if (evalCase.category() == null || evalCase.category().isBlank()) {
                throw new IllegalArgumentException("样本 " + evalCase.id() + " 的 category 为空");
            }
            if (evalCase.expectedKeywords() == null || evalCase.expectedKeywords().isEmpty()) {
                throw new IllegalArgumentException("样本 " + evalCase.id() + " 缺少期望关键词");
            }
            if (evalCase.expectedKeywords().stream().anyMatch(k -> k == null || k.isBlank())) {
                throw new IllegalArgumentException("样本 " + evalCase.id() + " 含空关键词");
            }
            if (evalCase.topK() != null && (evalCase.topK() < 1 || evalCase.topK() > 10)) {
                throw new IllegalArgumentException("样本 " + evalCase.id() + " 的 topK 必须在 1~10");
            }
        }
    }

    /** 按套件裁剪样本：smoke 取前 10 条，full 取全部；非法套件按 full 处理。 */
    public static List<EvalCase> selectSuite(List<EvalCase> cases, String suite) {
        if (cases == null) {
            return List.of();
        }
        if ("smoke".equalsIgnoreCase(suite)) {
            return cases.stream().limit(10).toList();
        }
        return cases;
    }

    /** 逐行解析 JSONL；解析失败带行号，避免坏数据静默丢失。 */
    private static List<EvalCase> readLines(BufferedReader reader) throws IOException {
        List<EvalCase> cases = new ArrayList<>();
        String line;
        int lineNo = 0;
        while ((line = reader.readLine()) != null) {
            lineNo++;
            if (line.isBlank() || line.trim().startsWith("#")) {
                continue;
            }
            try {
                cases.add(JsonUtil.fromJson(line, EvalCase.class));
            } catch (RuntimeException e) {
                throw new IllegalStateException("评测集第 " + lineNo + " 行解析失败", e);
            }
        }
        return cases;
    }
}
