package com.example.langchain4j.common.constant;

/**
 * 对话请求的公共选项常量与归一化。
 *
 * answerMode：
 *
 * - auto：标准问答链路，检索范围、回答预算和质检强度由图内证据决定；
 * - deep：调用方明确要求深度处理，放宽检索候选并提高质检强度。
 *
 * visibility：
 *
 * - all：公共知识库 + 当前用户知识库；
 * - private：仅当前用户知识库。
 *
 */
public final class ChatOptions {

    public static final String ANSWER_MODE_AUTO = "auto";
    public static final String ANSWER_MODE_DEEP = "deep";
    public static final String VISIBILITY_ALL = "all";
    public static final String VISIBILITY_PRIVATE = "private";

    private ChatOptions() {
    }

    /** 回答模式白名单归一：无法识别时回配置默认值，配置值也非法则回 auto。 */
    public static String normalizeAnswerMode(String value, String defaultMode) {
        if (ANSWER_MODE_DEEP.equalsIgnoreCase(value)) {
            return ANSWER_MODE_DEEP;
        }
        if (ANSWER_MODE_AUTO.equalsIgnoreCase(value)) {
            return ANSWER_MODE_AUTO;
        }
        return ANSWER_MODE_DEEP.equalsIgnoreCase(defaultMode) ? ANSWER_MODE_DEEP : ANSWER_MODE_AUTO;
    }

    /** 检索范围白名单归一：无法识别时回配置默认值，配置值也非法则回 all。 */
    public static String normalizeVisibility(String value, String defaultVisibility) {
        if (VISIBILITY_PRIVATE.equalsIgnoreCase(value)) {
            return VISIBILITY_PRIVATE;
        }
        if (VISIBILITY_ALL.equalsIgnoreCase(value)) {
            return VISIBILITY_ALL;
        }
        return VISIBILITY_PRIVATE.equalsIgnoreCase(defaultVisibility)
                ? VISIBILITY_PRIVATE : VISIBILITY_ALL;
    }
}
