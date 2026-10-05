package com.mhtextractor.platform;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@link HtmlWrapMap}（E2 软换行的"折行几何 ↔ 逻辑列"纯函数映射）的无头单测。
 *
 * <p>这是 E2 的核心换算内核：渲染切行、选择高亮分段、查找高亮切段、鼠标命中
 * 四条路径共用同一套投影，所以这里的边界语义（空行占一行 / 恰好整行的折行边界 /
 * 越界 clamp / 空区间）必须钉死成行为。</p>
 */
class HtmlWrapMapTest {

    // ==================== colsPerRow ====================

    @Test
    void colsPerRowFloorsToWholeCharacters() {
        assertEquals(75, HtmlWrapMap.colsPerRow(600, 8.0));
        assertEquals(75, HtmlWrapMap.colsPerRow(607.9, 8.0));   // 不足一格不凑整
    }

    @Test
    void colsPerRowNeverBelowOne() {
        assertEquals(1, HtmlWrapMap.colsPerRow(3, 8.0));
        assertEquals(1, HtmlWrapMap.colsPerRow(0, 8.0));
        assertEquals(1, HtmlWrapMap.colsPerRow(100, 0));    // 退化输入不炸
        assertEquals(1, HtmlWrapMap.colsPerRow(100, -1));
    }

    // ==================== visualRowCount ====================

    @Test
    void emptyLineOccupiesOneVisualRow() {
        assertEquals(1, HtmlWrapMap.visualRowCount(0, 5));
    }

    @Test
    void exactMultipleStaysOnOneRow() {
        assertEquals(1, HtmlWrapMap.visualRowCount(5, 5));
    }

    @Test
    void onePastBoundaryWraps() {
        assertEquals(2, HtmlWrapMap.visualRowCount(6, 5));
        assertEquals(2, HtmlWrapMap.visualRowCount(10, 5));
        assertEquals(3, HtmlWrapMap.visualRowCount(11, 5));
    }

    @Test
    void degenerateInputsKeepOneRow() {
        assertEquals(1, HtmlWrapMap.visualRowCount(-3, 5));
        // colsPerRow ≤ 0 按 1 处理：10 字符 = 10 个视觉行（与 colsPerRow(…) 至少为 1 同口径）
        assertEquals(10, HtmlWrapMap.visualRowCount(10, 0));
    }

    // ==================== visualRow / visualCol ====================

    @Test
    void columnProjectsToItsVisualRowAndColumn() {
        assertEquals(0, HtmlWrapMap.visualRow(4, 5));
        assertEquals(1, HtmlWrapMap.visualRow(5, 5));   // 第 5 个字符 = 第二折行的第 0 列
        assertEquals(2, HtmlWrapMap.visualRow(11, 5));
        assertEquals(4, HtmlWrapMap.visualCol(4, 5));
        assertEquals(0, HtmlWrapMap.visualCol(5, 5));
        assertEquals(1, HtmlWrapMap.visualCol(11, 5));
    }

    @Test
    void negativeColumnsClampToFirstVisualRow() {
        assertEquals(0, HtmlWrapMap.visualRow(-2, 5));
        assertEquals(0, HtmlWrapMap.visualCol(-2, 5));
    }

    // ==================== segments ====================

    @Test
    void emptySelectionHasNoSegments() {
        assertEquals(List.of(), segs(HtmlWrapMap.segments(10, 3, 3, 5)));
    }

    @Test
    void wholeLineSplitsIntoOneSegmentPerVisualRow() {
        assertEquals(List.of(List.of(0, 5), List.of(5, 10), List.of(10, 12)),
                segs(HtmlWrapMap.segments(12, 0, 12, 5)));
    }

    @Test
    void selectionCrossingTheBoundarySplitsInTwo() {
        assertEquals(List.of(List.of(3, 5), List.of(5, 8)),
                segs(HtmlWrapMap.segments(12, 3, 8, 5)));
    }

    @Test
    void selectionInsideOneVisualRowStaysWhole() {
        assertEquals(List.of(List.of(6, 9)),
                segs(HtmlWrapMap.segments(12, 6, 9, 5)));
    }

    @Test
    void outOfRangeColumnsClampToLineEnds() {
        assertEquals(List.of(List.of(0, 5), List.of(5, 10), List.of(10, 12)),
                segs(HtmlWrapMap.segments(12, -4, 99, 5)));
        assertEquals(List.of(), segs(HtmlWrapMap.segments(12, 50, 60, 5)));
    }

    @Test
    void emptyLineSelectionHasNoSegments() {
        assertEquals(List.of(), segs(HtmlWrapMap.segments(0, 0, 0, 5)));
    }

    @Test
    void boundaryAlignedEmptySelectionIsEmpty() {
        // 起点终点都在折行边界且重合（行长恰为边界整倍）→ 空选择，不产生零宽段
        assertEquals(List.of(), segs(HtmlWrapMap.segments(10, 5, 5, 5)));
    }

    // ==================== rows（自适应逐行贪心折行，2026-10-05） ====================

    @Test
    void emptyTextStillOccupiesOneVisualRow() {
        assertEquals(List.of(List.of(0, 0)), rowRanges(HtmlWrapMap.rows(new double[0], 100)));
    }

    @Test
    void equalWidthCharactersFillEachRowGreedily() {
        // 每字符 10px、内容区 25px → 每行放 2 个（3 个 = 30 超宽，1 个 = 10 没铺满）
        double[] adv = new double[7];
        java.util.Arrays.fill(adv, 10.0);
        assertEquals(List.of(List.of(0, 2), List.of(2, 4), List.of(4, 6), List.of(6, 7)),
                rowRanges(HtmlWrapMap.rows(adv, 25)));
    }

    @Test
    void wideAndNarrowCharactersShareTheSameRows() {
        // "AAAA中中中"：A=7px、中=13px、内容区 26px → 每行按真实宽度尽量多放（旧 uniform 网格做不到）
        double[] adv = {7, 7, 7, 7, 13, 13, 13};
        assertEquals(List.of(List.of(0, 3), List.of(3, 5), List.of(5, 7)),
                rowRanges(HtmlWrapMap.rows(adv, 26)));
    }

    @Test
    void aCharacterWiderThanTheContentStillAdvances() {
        // 退化输入（宽字 + 极窄内容区）：每行至少一个字符，绝不空转/死循环
        assertEquals(List.of(List.of(0, 1), List.of(1, 2)),
                rowRanges(HtmlWrapMap.rows(new double[]{100, 100}, 10)));
        assertEquals(List.of(List.of(0, 1), List.of(1, 2)),
                rowRanges(HtmlWrapMap.rows(new double[]{10, 10}, 0)));
    }

    @Test
    void zeroWidthUnitNeverStartsItsOwnRow() {
        // 代理对的第二个 code unit 宽为 0 → 必须和前一个 unit 同行（折行不切在代理对中间）
        assertEquals(List.of(List.of(0, 2), List.of(2, 3)),
                rowRanges(HtmlWrapMap.rows(new double[]{13, 0, 13}, 14)));
    }

    @Test
    void rowsCoverTheWholeLineWithoutGaps() {
        double[] adv = {7, 13, 7, 13, 7, 13, 7, 13, 7};
        List<int[]> rows = HtmlWrapMap.rows(adv, 20);
        assertEquals(0, rows.get(0)[0]);
        assertEquals(adv.length, rows.get(rows.size() - 1)[1]);
        for (int i = 1; i < rows.size(); i++) {
            assertEquals(rows.get(i - 1)[1], rows.get(i)[0], "行与行之间不能有空隙/重叠");
        }
    }

    /** 把 {@code int[]} 行区间转成 {@code List<List<Integer>>} 做<b>值</b>比较。 */
    private static List<List<Integer>> rowRanges(List<int[]> actual) {
        List<List<Integer>> out = new ArrayList<>(actual.size());
        for (int[] r : actual) {
            out.add(List.of(r[0], r[1]));
        }
        return out;
    }

    /** 把 {@code int[]} 段转成 {@code List<List<Integer>>} 做<b>值</b>比较（数组 equals 是引用比较）。 */
    private static List<List<Integer>> segs(List<int[]> actual) {
        List<List<Integer>> out = new ArrayList<>(actual.size());
        for (int[] s : actual) {
            out.add(List.of(s[0], s[1]));
        }
        return out;
    }
}
