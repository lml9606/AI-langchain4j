package com.example.langchain4j.common.util;

/**
 * 轻量文本处理：安全取值、trim 回退和截断。
 *
 * 这些逻辑在响应组装、任务确认和任务回答节点中重复出现；集中后行为一致，
 * 避免某处 trim、某处不 trim 导致展示结果不一致。
 */
public final class Texts {

    private Texts() {
    }

    /** null 回退默认值；非 null 原样转字符串，不 trim。 */
    public static String textOr(Object value, String defaultValue) {
        return value == null ? defaultValue : String.valueOf(value);
    }

    /** null 或 trim 后为空回退默认值。 */
    public static String trimmedTextOr(Object value, String defaultValue) {
        if (value == null) {
            return defaultValue;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? defaultValue : text;
    }

    /** 超长截断并追加省略号；null 返回空串。 */
    public static String truncate(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "...";
    }
}
