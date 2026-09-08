package com.mhtextractor.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 跨输出单元进度聚合测试（6.8，阶段 8）：总文件数求和、current 跨单元累计、整体进度换算。
 */
class UnitProgressAggregatorTest {

    @Test
    void totalFilesIsSumOfUnitSizes() {
        assertEquals(6, new UnitProgressAggregator(List.of(2, 3, 1)).totalFiles());
        assertEquals(0, new UnitProgressAggregator(List.of()).totalFiles());
    }

    @Test
    void globalCurrentAccumulatesAcrossUnits() {
        UnitProgressAggregator agg = new UnitProgressAggregator(List.of(2, 3, 1));
        assertEquals(1, agg.globalCurrent(0, 1));
        assertEquals(2, agg.globalCurrent(0, 2));
        assertEquals(3, agg.globalCurrent(1, 1));
        assertEquals(5, agg.globalCurrent(1, 3));
        assertEquals(6, agg.globalCurrent(2, 1));
    }

    @Test
    void overallUsesGlobalCurrentAndSubProgress() {
        UnitProgressAggregator agg = new UnitProgressAggregator(List.of(2, 3));
        assertEquals(0.0, agg.overall(0, 1, 0.0));
        assertEquals(0.4, agg.overall(0, 2, 1.0));       // 第 2 个文件完成 = 2/5
        assertEquals(0.4, agg.overall(1, 1, 0.0));       // 第 3 个文件开始 = 2/5
        assertEquals(0.7, agg.overall(1, 2, 0.5));       // (2+2-1+0.5)/5
        assertEquals(1.0, agg.overall(1, 3, 1.0));
    }

    @Test
    void emptyPlanYieldsZeroProgress() {
        UnitProgressAggregator agg = new UnitProgressAggregator(List.of());
        assertEquals(0.0, agg.overall(0, 1, 1.0));
    }
}
