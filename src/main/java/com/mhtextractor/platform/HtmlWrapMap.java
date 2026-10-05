package com.mhtextractor.platform;

import java.util.ArrayList;
import java.util.List;

/**
 * 软换行的<b>折行几何 ↔ 逻辑字符列</b>映射（E2 的纯函数内核，零 JavaFX、零第三方依赖）。
 *
 * <p>查看器的软换行按<b>字符网格</b>手工折行：等宽字体下每个字符等宽
 * （{@code HtmlSourceViewer#measureCharWidth()} 实测反推），一条 {@code len} 个
 * 逻辑字符的行每 {@code colsPerRow} 个切成一个视觉行。本类把这套换算集中成纯函数，
 * 供四条路径共用同一套坐标：</p>
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
