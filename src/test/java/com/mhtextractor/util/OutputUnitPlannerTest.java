package com.mhtextractor.util;

import com.mhtextractor.config.AppConfig;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 输出单元计划构建纯逻辑测试（6.4/6.8，阶段 8）：未分组按来源拆分、组单元、
 * 参数覆盖层解析、输出路径生成（组名/来源名 sanitize）、单元排序、空组与 removed 过滤。
 */
class OutputUnitPlannerTest {

    private record TItem(String path, String source, boolean removed,
                         AppConfig textParams, AppConfig imageParams) {
        TItem(String path, String source, boolean removed) {
            this(path, source, removed, null, null);
        }
    }

    private static final OutputUnitPlanner.ItemView<TItem> VIEW = new OutputUnitPlanner.ItemView<>() {
        @Override
        public String path(TItem item) {
            return item.path();
        }

        @Override
        public String sourceFolder(TItem item) {
            return item.source();
        }

        @Override
        public boolean removed(TItem item) {
            return item.removed();
        }

        @Override
        public AppConfig textItemParams(TItem item) {
            return item.textParams();
        }

        @Override
        public AppConfig imageItemParams(TItem item) {
            return item.imageParams();
        }
    };

    private static TItem item(String path, String source) {
        return new TItem(path, source, false, null, null);
    }

    private static TItem item(String path, String source, AppConfig textParams, AppConfig imageParams) {
        return new TItem(path, source, false, textParams, imageParams);
    }

    private static List<Object> order(Object... os) {
        return new ArrayList<>(Arrays.asList(os));
    }

    private static List<OutputUnitPlanner.OutputUnit> plan(List<?> dataOrder, boolean imageMode,
                                                           AppConfig textGlobal, AppConfig imageGlobal) {
        return OutputUnitPlanner.plan(dataOrder, VIEW, imageMode, textGlobal, imageGlobal, "S");
    }

    // ==================== 未分组：合并输出按来源拆分 ====================

    @Test
    void ungroupedMergedSingleSourceUsesLegacyNaming() {
        String source = "C:\\data\\小说A";
        List<OutputUnitPlanner.OutputUnit> units =
                plan(order(item("C:\\data\\小说A\\1.mht", source), item("C:\\data\\小说A\\2.mht", source)),
                        false, null, null);
        assertEquals(1, units.size());
        OutputUnitPlanner.OutputUnit u = units.get(0);
        // 与改造前 MainController 合并输出命名完全一致（无组、单来源回归）
        String legacy = new File("S", new File(source).getName() + ".txt").getPath();
        assertEquals(legacy, u.getOutputPath());
        assertEquals("小说A", u.getOutputName());
        assertFalse(u.isGroup());
        assertEquals(List.of("C:\\data\\小说A\\1.mht", "C:\\data\\小说A\\2.mht"), u.getPaths());
    }

    @Test
    void ungroupedMergedSplitsBySourceInFirstAppearanceOrder() {
        TItem a1 = item("A\\1.mht", "C:\\src\\A");
        TItem b1 = item("B\\1.mht", "C:\\src\\B");
        TItem a2 = item("A\\2.mht", "C:\\src\\A");
        List<OutputUnitPlanner.OutputUnit> units = plan(order(a1, b1, a2), false, null, null);
        assertEquals(2, units.size());
        assertEquals("A", units.get(0).getOutputName());
        assertEquals(List.of(a1.path(), a2.path()), units.get(0).getPaths());
        assertEquals(new File("S", "A.txt").getPath(), units.get(0).getOutputPath());
        assertEquals("B", units.get(1).getOutputName());
        assertEquals(List.of(b1.path()), units.get(1).getPaths());
    }

    @Test
    void ungroupedMergedSanitizesSourceNameAndFallsBack() {
        assertEquals("a_b", plan(order(item("x.mht", "C:\\a:b")), false, null, null).get(0).getOutputName());
        assertEquals("mht_output",
                plan(order(item("x.mht", "")), false, null, null).get(0).getOutputName());
        AppConfig imgMerged = new AppConfig();
        imgMerged.setSingleOutputMode(true);
        assertEquals("All_Images",
                plan(order(item("x.mht", null)), true, null, imgMerged).get(0).getOutputName());
    }

    @Test
    void ungroupedSeparateIsSingleUnitWritingToSessionDir() {
        AppConfig global = new AppConfig();
        global.setSeparateFiles(true);
        List<OutputUnitPlanner.OutputUnit> units =
                plan(order(item("A\\1.mht", "C:\\src\\A"), item("B\\1.mht", "C:\\src\\B")),
                        false, global, null);
        assertEquals(1, units.size());
        OutputUnitPlanner.OutputUnit u = units.get(0);
        assertNull(u.getOutputName());
        assertEquals("S", u.getOutputPath());
        assertEquals(2, u.fileCount());
        assertFalse(u.isGroup());
    }

    // ==================== 组单元 ====================

    @Test
    void groupUnitMergedTextUsesGroupNameAndOwnParams() {
        GroupItem<TItem> g = new GroupItem<>(1, "组1");
        AppConfig groupCfg = new AppConfig();
        groupCfg.setSeparateFiles(false);
        groupCfg.setStartMarker("MARK");
        g.setTextParamsCache(groupCfg);
        g.getMembers().addAll(item("x\\1.mht", "C:\\src"), item("x\\2.mht", "C:\\src"));

        List<OutputUnitPlanner.OutputUnit> units = plan(order(g), false, null, null);
        assertEquals(1, units.size());
        OutputUnitPlanner.OutputUnit u = units.get(0);
        assertTrue(u.isGroup());
        assertEquals("组1", u.getOutputName());
        assertEquals(new File("S", "组1.txt").getPath(), u.getOutputPath());
        assertSame(groupCfg, u.getParams());
        assertEquals(2, u.fileCount());
    }

    @Test
    void groupUnitSeparateOutputsToGroupFolder() {
        GroupItem<TItem> g = new GroupItem<>(2, "组2");
        AppConfig groupCfg = new AppConfig();
        groupCfg.setSeparateFiles(true);
        g.setTextParamsCache(groupCfg);
        g.getMembers().add(item("x\\1.mht", "C:\\src"));

        List<OutputUnitPlanner.OutputUnit> units = plan(order(g), false, null, null);
        assertEquals(new File("S", "组2").getPath(), units.get(0).getOutputPath());
    }

    @Test
    void groupUnitInheritsGlobalWhenUnset() {
        GroupItem<TItem> g = new GroupItem<>(1, "组1");
        g.getMembers().add(item("x\\1.mht", "C:\\src"));
        AppConfig global = new AppConfig();
        global.setSeparateFiles(false);

        List<OutputUnitPlanner.OutputUnit> units = plan(order(g), false, global, null);
        assertSame(global, units.get(0).getParams());
    }

    @Test
    void groupNameSanitizedBeforePathBuilding() {
        GroupItem<TItem> g = new GroupItem<>(3, "组3:a/b");
        g.getMembers().add(item("x\\1.mht", "C:\\src"));
        assertEquals("组3_a_b", plan(order(g), false, null, null).get(0).getOutputName());
        assertEquals(new File("S", "组3_a_b.txt").getPath(),
                plan(order(g), false, null, null).get(0).getOutputPath());
    }

    @Test
    void blankGroupNameFallsBackToGroupNo() {
        GroupItem<TItem> g = new GroupItem<>(5, "   ");
        g.getMembers().add(item("x\\1.mht", "C:\\src"));
        assertEquals("组5", plan(order(g), false, null, null).get(0).getOutputName());
    }

    @Test
    void imageModeMergedOutputsToGroupDirWithoutExtension() {
        GroupItem<TItem> g = new GroupItem<>(1, "组1");
        AppConfig imageCfg = new AppConfig();
        imageCfg.setSingleOutputMode(true);
        g.setImageParamsCache(imageCfg);
        g.getMembers().add(item("x\\1.mht", "C:\\src"));

        List<OutputUnitPlanner.OutputUnit> units = plan(order(g), true, null, null);
        assertEquals(new File("S", "组1").getPath(), units.get(0).getOutputPath());
        assertSame(imageCfg, units.get(0).getParams());
    }

    @Test
    void imageModeSeparateUsesGroupFolderAndSessionDirForUngrouped() {
        AppConfig imageGlobal = new AppConfig();
        imageGlobal.setSingleOutputMode(false);
        GroupItem<TItem> g = new GroupItem<>(1, "组1");
        g.getMembers().add(item("x\\1.mht", "C:\\src"));
        TItem top = item("y\\1.mht", "C:\\src2");

        List<OutputUnitPlanner.OutputUnit> units = plan(order(top, g), true, null, imageGlobal);
        assertEquals(2, units.size());
        assertEquals("S", units.get(0).getOutputPath());
        assertNull(units.get(0).getOutputName());
        assertEquals(new File("S", "组1").getPath(), units.get(1).getOutputPath());
    }

    // ==================== 单元排序 / removed 过滤 / 空组 ====================

    @Test
    void unitsOrderedByFirstAppearanceInDataOrder() {
        TItem a1 = item("A\\1.mht", "C:\\src\\A");
        GroupItem<TItem> g1 = new GroupItem<>(1, "组1");
        g1.getMembers().add(item("G\\1.mht", "C:\\src\\G"));
        TItem a2 = item("A\\2.mht", "C:\\src\\A");
        GroupItem<TItem> g2 = new GroupItem<>(2, "组2");
        g2.getMembers().add(item("H\\1.mht", "C:\\src\\H"));

        List<OutputUnitPlanner.OutputUnit> units = plan(order(a1, g1, a2, g2), false, null, null);
        assertEquals(3, units.size());
        assertEquals("A", units.get(0).getOutputName());
        assertTrue(units.get(1).isGroup());
        assertEquals("组1", units.get(1).getOutputName());
        assertEquals("组2", units.get(2).getOutputName());
    }

    @Test
    void removedItemsExcludedAndEmptyGroupSkipped() {
        GroupItem<TItem> g = new GroupItem<>(1, "组1");
        g.getMembers().add(new TItem("G\\1.mht", "C:\\src\\G", true));
        TItem top = item("A\\1.mht", "C:\\src\\A");
        TItem removed = new TItem("A\\2.mht", "C:\\src\\A", true);

        List<OutputUnitPlanner.OutputUnit> units = plan(order(top, g, removed), false, null, null);
        assertEquals(1, units.size());
        assertFalse(units.get(0).isGroup());
        assertEquals(List.of(top.path()), units.get(0).getPaths());
    }

    // ==================== 参数回退默认 ====================

    @Test
    void defaultsWhenNothingConfirmed() {
        List<OutputUnitPlanner.OutputUnit> units =
                plan(order(item("A\\1.mht", "C:\\src\\A")), false, null, null);
        AppConfig p = units.get(0).getParams();
        assertNotNull(p);
        assertFalse(p.isSeparateFiles()); // 默认合并 → 与改造前默认行为一致
    }

    // ==================== 条目独立参数分桶（条目独立参数需求拍板） ====================

    @Test
    void ungroupedSameSourceDifferentParamsSplitUnitsWithSuffix() {
        String source = "C:\\data\\小说A";
        AppConfig paramsX = new AppConfig();
        paramsX.setStartMarker("X");
        AppConfig paramsY = new AppConfig();
        paramsY.setStartMarker("Y");
        List<OutputUnitPlanner.OutputUnit> units = plan(order(
                item("C:\\data\\小说A\\1.mht", source, paramsX, null),
                item("C:\\data\\小说A\\2.mht", source, paramsY, null)), false, null, null);
        assertEquals(2, units.size());
        assertEquals("小说A", units.get(0).getOutputName());
        assertEquals("小说A(2)", units.get(1).getOutputName());
        assertEquals(new File("S", "小说A.txt").getPath(), units.get(0).getOutputPath());
        assertEquals(new File("S", "小说A(2).txt").getPath(), units.get(1).getOutputPath());
        assertSame(paramsX, units.get(0).getParams());
        assertSame(paramsY, units.get(1).getParams());
    }

    @Test
    void ungroupedSameParamsContentMergesIntoOneUnit() {
        String source = "C:\\data\\小说A";
        AppConfig paramsX1 = new AppConfig();
        paramsX1.setStartMarker("X");
        AppConfig paramsX2 = new AppConfig();
        paramsX2.setStartMarker("X"); // 内容相同 → 同桶
        List<OutputUnitPlanner.OutputUnit> units = plan(order(
                item("C:\\data\\小说A\\1.mht", source, paramsX1, null),
                item("C:\\data\\小说A\\2.mht", source, paramsX2, null)), false, null, null);
        assertEquals(1, units.size());
        assertEquals(2, units.get(0).fileCount());
        assertEquals("小说A", units.get(0).getOutputName());
    }

    @Test
    void ungroupedSeparateModeSplitsByParamsOnly() {
        AppConfig separate = new AppConfig();
        separate.setSeparateFiles(true);
        AppConfig merged = new AppConfig();
        merged.setSeparateFiles(false);
        List<OutputUnitPlanner.OutputUnit> units = plan(order(
                item("A\\1.mht", "C:\\src\\A", separate, null),
                item("A\\2.mht", "C:\\src\\A", merged, null)), false, null, null);
        assertEquals(2, units.size());
        // 单独输出桶：直写会话目录、输出名 null；合并输出桶：来源命名
        assertNull(units.get(0).getOutputName());
        assertEquals("S", units.get(0).getOutputPath());
        assertTrue(units.get(0).getParams().isSeparateFiles());
        assertEquals("A", units.get(1).getOutputName());
        assertEquals(new File("S", "A.txt").getPath(), units.get(1).getOutputPath());
        assertFalse(units.get(1).getParams().isSeparateFiles());
    }

    @Test
    void itemParamsBeatsGlobalInUnitParams() {
        AppConfig global = new AppConfig();
        global.setStartMarker("GLOBAL");
        AppConfig itemCfg = new AppConfig();
        itemCfg.setStartMarker("ITEM");
        List<OutputUnitPlanner.OutputUnit> units = plan(order(
                item("A\\1.mht", "C:\\src\\A", itemCfg, null),
                item("A\\2.mht", "C:\\src\\A")), false, global, null);
        assertEquals(2, units.size());
        // 参数内容不同 → 拆分；条目参数单元用条目参数，继承全局单元用全局
        assertSame(itemCfg, units.get(0).getParams());
        assertSame(global, units.get(1).getParams());
    }
}
