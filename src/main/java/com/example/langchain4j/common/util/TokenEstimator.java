package com.example.langchain4j.common.util;

/**
 * 粗略 token 估算：中文按 1 字符 ≈ 1 token，其余字符按 4 字符 ≈ 1 token。
 * <p>
 * 用途：生成视图按 token 预算装配历史窗口。生产级精确核算应接入真实 tokenizer
 * （如 langchain4j Tokenizer），本类收敛估算逻辑，替换点唯一。
 */
public final class TokenEstimator {

    private static final int LATIN_CHARS_PER_TOKEN = 4;

    private TokenEstimator() {
    }

    public static int estimate(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int latinRun = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (isCjk(c)) {
                // 中文逐字计 token；前面攒下的拉丁字符先结算
                tokens += (latinRun + LATIN_CHARS_PER_TOKEN - 1) / LATIN_CHARS_PER_TOKEN;
                latinRun = 0;
                tokens += 1;
            } else {
                latinRun += 1;
            }
        }
        tokens += (latinRun + LATIN_CHARS_PER_TOKEN - 1) / LATIN_CHARS_PER_TOKEN;
        return tokens;
    }

    /** CJK 统一表意文字区间（常用中文），其余按非中文处理 */
    private static boolean isCjk(char c) {
        return c >= 0x4E00 && c <= 0x9FFF;
    }
}
