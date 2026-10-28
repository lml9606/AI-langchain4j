package com.example.langchain4j.dto;

/**
 * 查询改写结果：改写句 + 话题归属（与改写合并为一次 LLM 调用输出）。
 *
 * @param rewritten 自包含改写句（无需改写时等于原始 query）
 * @param topic     当前输入归属的话题标签（新话题时为新标签；解析失败/未判断时为空）
 * @param newTopic  是否为全新话题（决定是否创建新话题段）
 */
public record RewriteResult(String rewritten, String topic, boolean newTopic) {

    /** 解析失败/降级时的空结果：改写句退回原句，话题归属不判断 */
    public static RewriteResult degrade(String original) {
        return new RewriteResult(original, null, false);
    }
}
