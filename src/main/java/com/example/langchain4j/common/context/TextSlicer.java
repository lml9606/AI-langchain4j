package com.example.langchain4j.common.context;

import com.example.langchain4j.common.util.TokenEstimator;

/**
 * 文本按 token 预算截断。
 *
 * 截断不是简单 substring：优先切在段落边界，其次句子边界，最后才按字符切；
 * 落点如果在未闭合的代码块里，会退回到代码块起始处，宁可少给内容也不给半段代码。
 * 这样模型拿到的是完整语义单元，而不是一句被劈开的话。
 */
public final class TextSlicer {

    /** 段落边界允许保留的最小比例；段落是最强语义边界，可以多丢一点。 */
    private static final int PARAGRAPH_KEEP_NUM = 3;
    private static final int PARAGRAPH_KEEP_DEN = 10;
    /** 句子边界允许保留的最小比例；低于一半说明会砍掉太多内容，宁可退回字符落点。 */
    private static final int SENTENCE_KEEP_NUM = 1;
    private static final int SENTENCE_KEEP_DEN = 2;

    private TextSlicer() {
    }

    /**
     * 把文本裁剪到不超过 maxTokens。
     *
     * @param text      原始文本，null 或空串返回空串
     * @param maxTokens 允许的最大 token 数，<=0 返回空串
     * @return 截断后的文本，保证不超过预算且尽量落在语义边界
     */
    public static String sliceToTokens(String text, int maxTokens) {
        if (text == null || text.isEmpty() || maxTokens <= 0) {
            return "";
        }
        // 步骤1：本来就装得下，原样返回，避免无意义的二次处理
        if (TokenEstimator.estimate(text) <= maxTokens) {
            return text;
        }
        // 步骤2：二分找满足预算的最大字符前缀；token 估算单调，二分成立
        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            if (TokenEstimator.estimate(text.substring(0, mid)) <= maxTokens) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        if (low <= 0) {
            return "";
        }
        // 步骤3：从字符落点回退到语义边界，再避开未闭合的代码块
        int cut = preferBoundary(text, low);
        cut = avoidInsideCodeFence(text, cut);
        if (cut <= 0) {
            return "";
        }
        return text.substring(0, cut).stripTrailing();
    }

    /** 依次尝试段落、句子、字符三种落点，返回可接受的最大者。 */
    private static int preferBoundary(String text, int cut) {
        int paragraphFloor = Math.max(1, cut * PARAGRAPH_KEEP_NUM / PARAGRAPH_KEEP_DEN);
        int paragraph = text.lastIndexOf("\n\n", cut - 1);
        if (paragraph >= paragraphFloor) {
            return paragraph;
        }
        int sentenceFloor = Math.max(1, cut * SENTENCE_KEEP_NUM / SENTENCE_KEEP_DEN);
        int sentence = lastSentenceBoundary(text, cut);
        if (sentence >= sentenceFloor) {
            return sentence;
        }
        return cut;
    }

    /** 找最后一个句子结束位置（含中文句号、问叹号和换行）。 */
    private static int lastSentenceBoundary(String text, int cut) {
        for (int i = cut - 1; i > 0; i--) {
            char c = text.charAt(i);
            if (c == '。' || c == '！' || c == '？' || c == '.' || c == '!' || c == '?'
                    || c == '\n' || c == '；' || c == ';') {
                return i + 1;
            }
        }
        return -1;
    }

    /** 落点位于未闭合代码块内时，退回到该代码块起始处，避免输出半段代码。 */
    private static int avoidInsideCodeFence(String text, int cut) {
        int fenceCount = 0;
        int lastFenceStart = -1;
        int index = 0;
        while (index < cut) {
            int found = text.indexOf("```", index);
            if (found < 0 || found >= cut) {
                break;
            }
            fenceCount++;
            lastFenceStart = found;
            index = found + 3;
        }
        boolean unclosed = fenceCount % 2 == 1;
        return unclosed && lastFenceStart > 0 ? lastFenceStart : cut;
    }
}
