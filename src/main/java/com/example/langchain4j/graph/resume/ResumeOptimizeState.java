package com.example.langchain4j.graph.resume;

import org.bsc.langgraph4j.state.AgentState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 简历优化工作流状态：LangGraph4j 图内共享的状态容器。
 * <p>
 * 与 {@link com.example.langchain4j.graph.state.WorkflowState} 同一套约定：
 * 节点不直接改状态，只返回 {@code Map<String,Object>} 增量，框架合并后生成新状态传给下一个节点。
 * <p>
 * 为什么单独建状态而不是复用 WorkflowState：简历优化没有多轮历史、检索上下文、
 * 多跳/质检环，状态字段与问答链路完全不同。混在一个状态里会污染现有 checkpoint
 * 迁移逻辑，也让两条图的路由和递归上限互相牵制。
 */
public class ResumeOptimizeState extends AgentState {

    /** 简历原文（服务层输入，EntryNode 清洗/截断后写回 RESUME_MD） */
    public static final String RESUME_RAW = "resumeRaw";
    /** 规范化后的 Markdown 简历（章节名已提升为 ## 标题，供目标画像/生成/校验使用） */
    public static final String RESUME_MD = "resumeMd";
    /** 优化模式：TARGETED / FREE（ResumeOptimizeMode） */
    public static final String MODE = "mode";
    /** 目标岗位列表（List<Map>：jobName/company/salary/jd/skills/labels） */
    public static final String TARGET_JOBS = "targetJobs";
    /** 岗位需求画像文本块（TargetProfileNode 写入，生成节点拼入提示词） */
    public static final String TARGET_BLOCK = "targetBlock";
    /** 自由模式选定章节（空 = 全部章节） */
    public static final String SELECTED_SECTIONS = "selectedSections";
    /** 自由模式优化提示词 */
    public static final String OPTIMIZATION_PROMPT = "optimizationPrompt";
    /** 修改后的整份简历（Markdown） */
    public static final String REVISED_MD = "revisedMd";
    /** 变更点清单（List<Map>：section/type/summary/reason） */
    public static final String CHANGES = "changes";
    /** 行级 diff 块（List<Map>：tag/left/right） */
    public static final String DIFF = "diff";
    /** 校验结果子对象（Validation record；checkpoint 反序列化后为 Map，访问器统一防御转换） */
    public static final String VALIDATION = "validation";
    /** 校验失败重试计数（EntryNode 归零，ValidateNode 失败 +1，Router 据此终止重试环） */
    public static final String RETRY_COUNT = "retryCount";
    /** 原文是否被截断（生成节点据此在提示词里明示） */
    public static final String INPUT_TRUNCATED = "inputTruncated";
    /** 模型输出是否被 maxOutputTokens 截断（服务层透出，便于前端提示换模型） */
    public static final String TRUNCATED = "truncated";
    /** 本轮生成消耗的 token 总数 */
    public static final String TOKEN_USAGE = "tokenUsage";
    /** 错误标记（成功路径清空；任一节点失败写入原因，Router 发现非空即转兜底） */
    public static final String ERROR = "error";
    /** 兜底标记（FallbackNode 写入；服务层据此返回错误而不是半份简历） */
    public static final String FALLBACK = "fallback";
    /** 流程完成标记（FinalizeNode 写入） */
    public static final String DONE = "done";
    /** 本轮实际使用的模型名（服务层解析后入图） */
    public static final String MODEL = "model";

    /**
     * 校验结果子对象：是否通过 + 失败类型 + 失败原因。
     * issue 取值：STRUCTURE（章节骨架丢失）/ FACT（事实锚点被改）/ FORMAT（JSON 或变更清单非法）。
     */
    public record Validation(boolean valid, String issue, String reason) {

        public static final String ISSUE_STRUCTURE = "STRUCTURE";
        public static final String ISSUE_FACT = "FACT";
        public static final String ISSUE_FORMAT = "FORMAT";

        static final String KEY_VALID = "valid";
        static final String KEY_ISSUE = "issue";
        static final String KEY_REASON = "reason";

        public static Validation of(boolean valid, String issue, String reason) {
            return new Validation(valid, issue == null ? "" : issue, reason == null ? "" : reason);
        }
    }

    public ResumeOptimizeState(Map<String, Object> initData) {
        super(initData == null ? Map.of() : initData);
    }

    public String resumeRaw() {
        return value(RESUME_RAW, "");
    }

    public String resumeMd() {
        return value(RESUME_MD, "");
    }

    public String mode() {
        return value(MODE, "");
    }

    public String targetBlock() {
        return value(TARGET_BLOCK, "");
    }

    public String optimizationPrompt() {
        return value(OPTIMIZATION_PROMPT, "");
    }

    public String revisedMd() {
        return value(REVISED_MD, "");
    }

    public String model() {
        return value(MODEL, "");
    }

    public String error() {
        return value(ERROR, "");
    }

    public boolean inputTruncated() {
        return Boolean.parseBoolean(String.valueOf(value(INPUT_TRUNCATED).orElse(false)));
    }

    public boolean truncated() {
        return Boolean.parseBoolean(String.valueOf(value(TRUNCATED).orElse(false)));
    }

    public boolean fallback() {
        return Boolean.parseBoolean(String.valueOf(value(FALLBACK).orElse(false)));
    }

    public boolean done() {
        return Boolean.parseBoolean(String.valueOf(value(DONE).orElse(false)));
    }

    /** 校验失败重试计数；防御 checkpoint 反序列化后的类型漂移 */
    public int retryCount() {
        Object v = value(RETRY_COUNT, 0);
        if (v instanceof Number number) {
            return number.intValue();
        }
        try {
            return Integer.parseInt(String.valueOf(v).trim());
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    public Integer tokenUsage() {
        Object v = value(TOKEN_USAGE).orElse(null);
        return v instanceof Number number ? number.intValue() : null;
    }

    /** 目标岗位列表；防御 checkpoint 反序列化后的类型漂移，只接受 List<Map> */
    public List<Map<String, Object>> targetJobs() {
        return mapList(TARGET_JOBS);
    }

    /** 自由模式选定章节；只接受 List，逐项转字符串并过滤空白 */
    public List<String> selectedSections() {
        return stringList(SELECTED_SECTIONS);
    }

    /** 变更点清单；只接受 List<Map> */
    public List<Map<String, Object>> changes() {
        return mapList(CHANGES);
    }

    /** 行级 diff 块；只接受 List<Map> */
    public List<Map<String, Object>> diff() {
        return mapList(DIFF);
    }

    /**
     * 校验结果：优先读内存态 record；checkpoint 反序列化后为 Map，逐 key 防御解析；
     * 缺失时返回「未校验」的默认值。
     */
    public Validation validation() {
        Object v = value(VALIDATION).orElse(null);
        if (v instanceof Validation validation) {
            return validation;
        }
        if (v instanceof Map<?, ?> map) {
            return Validation.of(
                    boolOf(map.get(Validation.KEY_VALID)),
                    strOf(map.get(Validation.KEY_ISSUE)),
                    strOf(map.get(Validation.KEY_REASON)));
        }
        return Validation.of(false, "", "");
    }

    private List<String> stringList(String key) {
        Object v = value(key).orElse(null);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item != null && !String.valueOf(item).isBlank()) {
                result.add(String.valueOf(item));
            }
        }
        return result;
    }

    private List<Map<String, Object>> mapList(String key) {
        Object v = value(key).orElse(null);
        if (!(v instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (item instanceof Map<?, ?> map) {
                Map<String, Object> entry = new LinkedHashMap<>(map.size());
                map.forEach((k, val) -> entry.put(String.valueOf(k), val));
                result.add(entry);
            }
        }
        return result;
    }

    private static boolean boolOf(Object v) {
        if (v instanceof Boolean b) {
            return b;
        }
        return v != null && Boolean.parseBoolean(String.valueOf(v).trim());
    }

    private static String strOf(Object v) {
        return v == null ? "" : String.valueOf(v);
    }
}
