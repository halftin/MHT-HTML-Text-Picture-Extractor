package com.mhtextractor.platform;

import java.util.List;

/**
 * HTML 源码查看器的<b>纯逻辑字符选择模型</b>（E1）：坐标是 (行, 列) 的<b>字符偏移</b>，
 * 与渲染层（JavaFX）完全无关，因此可以在无头单测里直接验证；E2 显示折行后要复用的
 * 也是这一套"选择范围 → 文本"的逻辑字符偏移。
 *
 * <p>约定：{@link Pos#line} 与 {@link Range} 的行、列都是 0 基；范围是
 * <b>半开区间</b>——覆盖第 {@code [startLine, endLine]} 行，行内覆盖第
 * {@code [startCol, endCol)} 列（"第 col 个字符之前"）。</p>
 */
public final class HtmlTextSelection {

    private HtmlTextSelection() {
    }

    /** 一个字符位置：第 {@code line} 行（0 基）的第 {@code col} 列（0 基，指"第 col 个字符之前"）。 */
    public record Pos(int line, int col) {
    }

    /** 归一化后的选择范围：{@code start <= end}（先比行、再比列）。 */
    public record Range(int startLine, int startCol, int endLine, int endCol) {
    }

    /** 把任意两个位置归一化成"起点在前"的范围。 */
    public static Range ordered(Pos a, Pos b) {
        if (a.line() < b.line() || (a.line() == b.line() && a.col() <= b.col())) {
            return new Range(a.line(), a.col(), b.line(), b.col());
        }
        return new Range(b.line(), b.col(), a.line(), a.col());
    }

    /** 空选择（起点与终点重合，不覆盖任何字符）。 */
    public static boolean isEmpty(Range r) {
        return r != null && r.startLine() == r.endLine() && r.startCol() == r.endCol();
    }

    /**
     * 从逐行纯文本里抽出选择范围内的原文。
     *
     * <ul>
     *   <li>越界列 clamp 到 {@code [0, 该行字符数]}；</li>
     *   <li>单行 = 该行子串；多行 = 首行尾段 + 中间整行 + 末行首段，行之间用单个 LF({@code \n}) 连接；</li>
     *   <li>空行原样保留（产生连续 LF）；缩进与行尾空格逐字符保留。</li>
     * </ul>
     */
    public static String extract(List<String> plainLines, Range r) {
        if (r == null || plainLines.isEmpty() || r.endLine() < 0 || r.startLine() >= plainLines.size()) {
            return "";   // 整个范围都在文本之外：什么也没选中
        }
        int last = plainLines.size() - 1;
        int startLine = Math.max(0, Math.min(r.startLine(), last));
        int endLine = Math.max(0, Math.min(r.endLine(), last));
        if (startLine > endLine) {
            return "";   // 整个范围都在文本之外
        }
        if (startLine == endLine) {
            String line = plainLines.get(startLine);
            int s = clamp(r.startCol(), line.length());
            int e = clamp(r.endCol(), line.length());
            return e > s ? line.substring(s, e) : "";
        }
        StringBuilder sb = new StringBuilder();
        String first = plainLines.get(startLine);
        sb.append(first.substring(clamp(r.startCol(), first.length())));
        for (int i = startLine + 1; i < endLine; i++) {
            sb.append('\n').append(plainLines.get(i));
        }
        String lastLine = plainLines.get(endLine);
        sb.append('\n').append(lastLine, 0, clamp(r.endCol(), lastLine.length()));
        return sb.toString();
    }

    private static int clamp(int col, int length) {
        return Math.max(0, Math.min(col, length));
    }
}
