package com.mhtextractor.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * 软换行的<b>折行几何 ↔ 逻辑字符列</b>映射（E2 的纯函数内核，零 JavaFX、零第三方依赖）。
 *
 * <p><b>折行模型（2026-10-05 起自适应）</b>：查看器按"<b>逐行贪心</b>"折行 ——
 * {@link #rows} 拿到每个字符的<b>真实像素宽</b>后，每行尽量多放字符，直到再加一个就超宽。
 * 因为等宽字体的 CJK 是全宽（≈字号）、拉丁是半宽，一条纯 ASCII 行以前只能用到面板约一半宽，
 * 自适应后能铺满；同时"每行都不超宽"是硬约束（超宽会被定高的视觉行裁掉 → 丢字）。</p>
 *
 * <p>原有的<b>字符网格</b>换算（{@link #colsPerRow} / {@link #visualRowCount} /
 * {@link #visualRow} / {@link #visualCol} / {@link #segments}）保留为纯函数：它们是
 * "所有字符等宽"这一理想模型的投影，供单测与格点调用方使用；查看器自身不再用它们定坐标
 * （高亮按 run 真实宽度、折行按 {@link #rows}）。本类不产生第二套坐标，只做投影换算：
 * 所有列号都是 0 基<b>逻辑字符偏移</b>。</p>
 * <ul>
 *   <li>渲染：把一行切成一视觉行一个 {@code TextFlow}；</li>
 *   <li>选择/查找高亮的<b>逻辑区间切段</b>（{@link #segments}）：纯网格投影，供格点几何的调用方与单测使用。</li>
 *   <li>（2026-10-05 起）查看器的高亮矩形改按每个 {@code Text} run 的<b>真实布局宽</b>定位，
 *       不再用网格宽度猜字形位置 —— 比例回退字体、CJK 与拉丁混排时网格会偏（见 HtmlSourceViewer）。</li>
 *   <li>鼠标命中：像素 → 视觉行/列 → 逻辑列。</li>
 * </ul>
 *
 * <p>显示折行只是逻辑 {@code (line, col)} 坐标（与 {@link HtmlTextSelection} 同一套）的
 * <b>投影</b>——本类不产生第二套坐标，只做投影换算。约定：所有列号都是 0 基<b>逻辑字符
 * 偏移</b>；"视觉行号"是某逻辑行<b>内部</b>的 0 基折行行号，"视觉列"是该折行内的 0 基列。</p>
 */
public final class HtmlWrapMap {

    private HtmlWrapMap() {
    }

    /**
     * <b>逐行贪心折行</b>：把一条逻辑行按每个字符的真实像素宽切成若干视觉行，
     * 返回每行的 {@code [start, end)} 逻辑列半开区间。
     *
     * <p>规则：当前行从 {@code start} 起尽量多放字符，直到"再加上下一个字符就超过
     * {@code contentWidth}"为止；<b>每行至少放一个字符</b>（即使它本身比内容区还宽），
     * 否则宽字符/极窄视口会死循环。空文本也返回一个 {@code [0,0)} 的空行
     * （空逻辑行在界面上仍占一个视觉行）。</p>
     *
     * @param advances     每个<b>UTF-16 code unit</b> 的像素宽（长度 = 文本长度；代理对的第二个
     *                     unit 传 0，这样折行不会落在代理对中间）
     * @param contentWidth 内容区像素宽（行号栏之后、扣除右侧安全留白）
     */
    public static List<int[]> rows(double[] advances, double contentWidth) {
        List<int[]> out = new ArrayList<>();
        int len = advances == null ? 0 : advances.length;
        if (len == 0) {
            out.add(new int[]{0, 0});
            return out;
        }
        int start = 0;
        while (start < len) {
            int end = start;
            double w = 0;
            while (end < len) {
                double adv = Math.max(0, advances[end]);
                if (end > start && w + adv > contentWidth) {
                    break;
                }
                w += adv;
                end++;
            }
            if (end == start) {
                end = start + 1;   // 退化输入（宽度非正/NaN）也要前进
            }
            out.add(new int[]{start, end});
            start = end;
        }
        return out;
    }

    /** 每视觉行的列数：内容视口像素宽 ÷ 单字符像素宽，向下取整、至少 1（防除零与退化输入）。 */
    public static int colsPerRow(double contentViewportPx, double charWidth) {
        if (!(charWidth > 0)) {
            return 1;
        }
        return Math.max(1, (int) Math.floor(contentViewportPx / charWidth));
    }

    /** 一条 {@code logicalLineLength} 字符的逻辑行占几个视觉行（空行也占 1 行）。 */
    public static int visualRowCount(int logicalLineLength, int colsPerRow) {
        int cols = Math.max(1, colsPerRow);
        int len = Math.max(0, logicalLineLength);
        return Math.max(1, (len + cols - 1) / cols);
    }

    /** 逻辑列 {@code col} 落在第几个视觉行（0 基）。 */
    public static int visualRow(int col, int colsPerRow) {
        return Math.max(0, col) / Math.max(1, colsPerRow);
    }

    /** 逻辑列 {@code col} 在其视觉行内的列（0 基）。 */
    public static int visualCol(int col, int colsPerRow) {
        return Math.max(0, col) % Math.max(1, colsPerRow);
    }

    /**
     * 把逻辑行内的区间 {@code [fromLogicalCol, toLogicalCol)} 切成"每视觉行一段"，
     * 每段是 {@code [start, end)} 的<b>逻辑列</b>半开区间；空区间 / 完全越界 → 空列表。
     *
     * <p>调用方给每个段画一个矩形：x = 视觉行起点 + (段起点 − 该视觉行首列) × 字符宽，
     * y = 视觉行号 × 行高，宽 = (段终点 − 段起点) × 字符宽，高 = 行高。</p>
     *
     * <p>⚠️ 这是<b>格点</b>投影：要求每个字符等宽、且与量出来的 {@code charWidth} 一致。
     * 查看器的高亮自 2026-10-05 起改按 run 真实宽度定位（见 {@code HtmlSourceViewer#paintRunRects}），
     * 本方法保留为纯函数/单测与格点调用方使用。</p>
     */
    public static List<int[]> segments(int logicalLineLength, int fromLogicalCol,
                                       int toLogicalCol, int colsPerRow) {
        List<int[]> out = new ArrayList<>();
        int cols = Math.max(1, colsPerRow);
        int len = Math.max(0, logicalLineLength);
        int from = Math.max(0, Math.min(fromLogicalCol, len));
        int to = Math.max(0, Math.min(toLogicalCol, len));
        if (from >= to) {
            return out;
        }
        int first = from / cols;
        int last = (to - 1) / cols;
        for (int v = first; v <= last; v++) {
            int s = Math.max(from, v * cols);
            int e = Math.min(to, (v + 1) * cols);
            out.add(new int[]{s, e});
        }
        return out;
    }
}
