package com.example.langchain4j.graph.resume.util;

import com.example.langchain4j.graph.resume.model.ResumeDiffBlock;

import java.util.ArrayList;
import java.util.List;

/**
 * 行级 diff：按 LCS（最长公共子序列）对齐原文与改稿，输出可直接给前端渲染的块。
 * <p>
 * 不引入第三方 diff 库：简历行数有限（通常几十到几百行），
 * 这里的 O(n*m) DP 足够；超过安全阈值时退化为单个 replace 块，防止大输入打爆内存。
 */
public final class LineDiffUtil {

    /** DP 单元上限：约 4M 个 int（16MB），超过则不做逐行对齐 */
    private static final long MAX_DP_CELLS = 4_000_000L;
    /** 输出块上限：超过则退化为单个 replace 块，避免响应体膨胀 */
    private static final int MAX_BLOCKS = 500;

    public static final String TAG_SAME = "same";
    public static final String TAG_REPLACE = "replace";
    public static final String TAG_DELETE = "delete";
    public static final String TAG_INSERT = "insert";

    private LineDiffUtil() {
    }

    /**
     * 计算行级 diff。
     *
     * @param original 原文（可为 null）
     * @param revised  改稿（可为 null）
     * @return 对齐块列表；两边都为空时返回空列表
     */
    public static List<ResumeDiffBlock> diff(String original, String revised) {
        String leftText = original == null ? "" : original;
        String rightText = revised == null ? "" : revised;
        if (leftText.isEmpty() && rightText.isEmpty()) {
            return List.of();
        }
        String[] left = splitLines(leftText);
        String[] right = splitLines(rightText);
        if ((long) left.length * right.length > MAX_DP_CELLS) {
            return List.of(new ResumeDiffBlock(TAG_REPLACE, leftText, rightText));
        }

        int[][] lcs = new int[left.length + 1][right.length + 1];
        for (int i = left.length - 1; i >= 0; i--) {
            for (int j = right.length - 1; j >= 0; j--) {
                lcs[i][j] = left[i].equals(right[j])
                        ? lcs[i + 1][j + 1] + 1
                        : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
            }
        }

        List<Op> ops = new ArrayList<>();
        int i = 0;
        int j = 0;
        while (i < left.length && j < right.length) {
            if (left[i].equals(right[j])) {
                ops.add(new Op(TAG_SAME, left[i]));
                i++;
                j++;
            } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                ops.add(new Op(TAG_DELETE, left[i]));
                i++;
            } else {
                ops.add(new Op(TAG_INSERT, right[j]));
                j++;
            }
        }
        while (i < left.length) {
            ops.add(new Op(TAG_DELETE, left[i++]));
        }
        while (j < right.length) {
            ops.add(new Op(TAG_INSERT, right[j++]));
        }

        List<ResumeDiffBlock> blocks = merge(ops);
        if (blocks.size() > MAX_BLOCKS) {
            return List.of(new ResumeDiffBlock(TAG_REPLACE, leftText, rightText));
        }
        return blocks;
    }

    private static String[] splitLines(String text) {
        return text.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
    }

    /** 连续同类行合并；相邻的 delete + insert 合并为一个 replace 块 */
    private static List<ResumeDiffBlock> merge(List<Op> ops) {
        List<ResumeDiffBlock> blocks = new ArrayList<>();
        int i = 0;
        while (i < ops.size()) {
            if (TAG_SAME.equals(ops.get(i).tag())) {
                List<String> lines = new ArrayList<>();
                while (i < ops.size() && TAG_SAME.equals(ops.get(i).tag())) {
                    lines.add(ops.get(i++).line());
                }
                String text = String.join("\n", lines);
                blocks.add(new ResumeDiffBlock(TAG_SAME, text, text));
                continue;
            }
            List<String> deleted = new ArrayList<>();
            List<String> inserted = new ArrayList<>();
            while (i < ops.size() && !TAG_SAME.equals(ops.get(i).tag())) {
                Op op = ops.get(i++);
                if (TAG_DELETE.equals(op.tag())) {
                    deleted.add(op.line());
                } else {
                    inserted.add(op.line());
                }
            }
            if (!deleted.isEmpty() && !inserted.isEmpty()) {
                blocks.add(new ResumeDiffBlock(TAG_REPLACE,
                        String.join("\n", deleted), String.join("\n", inserted)));
            } else if (!deleted.isEmpty()) {
                blocks.add(new ResumeDiffBlock(TAG_DELETE, String.join("\n", deleted), ""));
            } else {
                blocks.add(new ResumeDiffBlock(TAG_INSERT, "", String.join("\n", inserted)));
            }
        }
        return blocks;
    }

    private record Op(String tag, String line) {
    }
}
