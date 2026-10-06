package com.mhtextractor.app;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.OutputUnitPlanner;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ExtractionPlanService} 的无头回归（P3-3b-3）：运行时配置组装（null 回落 + 就地修改缓存实例）
 * 与输出单元计划（勾选过滤 + 逐单元运行时字段补充）—— 与搬迁前
 * {@code ExtractionTaskCoordinator.buildRuntimeConfig / startExtractionTask} 的纯计算段同构。
 *
 * <p><b>零 JavaFX</b>：本类不启动工具包，也不引用任何 {@code javafx.*}。</p>
 */
class ExtractionPlanServiceTest {

    private static PreviewItem item(String path, String source, String name) {
        return new PreviewItem(path, name, LocalDateTime.now(), source);
    }

    // ==================== runtimeConfig ====================

    /** 缓存非 null 时就地修改该实例（搬迁前语义），四个运行时字段按序落位；未选中模式的缓存不被触碰。 */
    @Test
    void runtimeConfigReusesCachedInstanceAndSetsRuntimeFields() {
        AppConfig image = new AppConfig();
        AppConfig text = new AppConfig();
        AppConfig out = ExtractionPlanService.runtimeConfig(true, image, text, "in", "out", true);
        assertSame(image, out);
        assertEquals("in", out.getInputFolder());
        assertEquals("out", out.getOutputFolder());
        assertTrue(out.isImageModeEnabled());
        assertTrue(out.isOpenFolderAfterComplete());
        assertNull(text.getInputFolder());
        assertNull(text.getOutputFolder());
        assertFalse(text.isImageModeEnabled());
        assertFalse(text.isOpenFolderAfterComplete());
    }

    /** 当前模式缓存为 null → 新建默认实例并补运行时字段（文本模式同理）。 */
    @Test
    void runtimeConfigNullCacheFallsBackToDefaultInstance() {
        AppConfig out = ExtractionPlanService.runtimeConfig(true, null, new AppConfig(), "in", "out", false);
        assertNotNull(out);
        assertEquals("in", out.getInputFolder());
        assertEquals("out", out.getOutputFolder());
        assertTrue(out.isImageModeEnabled());
        assertFalse(out.isOpenFolderAfterComplete());

        AppConfig text = new AppConfig();
        AppConfig textOut = ExtractionPlanService.runtimeConfig(false, null, text, "in2", "out2", true);
        assertSame(text, textOut);
        assertEquals("in2", textOut.getInputFolder());
        assertEquals("out2", textOut.getOutputFolder());
        assertFalse(textOut.isImageModeEnabled());
        assertTrue(textOut.isOpenFolderAfterComplete());
    }

    // ==================== plan ====================

    /** 每个输出单元的生效参数都被按序补上运行时字段（setInputFolder → setOutputFolder → setImageModeEnabled → setOpenFolderAfterComplete）。 */
    @Test
    void planSupplementsRuntimeFieldsOnEveryUnit() {
        PreviewItem a = item("C:/x/a.mht", "C:/x", "a.mht");
        PreviewItem b = item("C:/y/b.mht", "C:/y", "b.mht");
        AppConfig text = new AppConfig();
        text.setSeparateFiles(false);   // 合并模式：两个来源 → 两个单元
        var units = ExtractionPlanService.plan(List.of(a, b), false, text, null,
                "session", "in", "out", true, false);
        assertEquals(2, units.size());
        for (var u : units) {
            AppConfig p = u.getParams();
            assertEquals("in", p.getInputFolder());
            assertEquals("out", p.getOutputFolder());
            assertFalse(p.isImageModeEnabled());
            assertTrue(p.isOpenFolderAfterComplete());
        }
    }

    /** 功能一：checkedOnly=true 时未勾选条目不进任何单元；false 时全部参与（视图默认 checked 恒 true）。 */
    @Test
    void planCheckedOnlyFiltersUncheckedItems() {
        PreviewItem a = item("C:/x/a.mht", "C:/x", "a.mht");
        PreviewItem b = item("C:/x/b.mht", "C:/x", "b.mht");
        a.selected = true;
        b.selected = false;
        AppConfig text = new AppConfig();
        text.setSeparateFiles(false);
        java.util.function.Function<List<OutputUnitPlanner.OutputUnit>, List<String>> pathsOf =
                units -> units.stream().flatMap(u -> u.getPaths().stream()).toList();

        var all = ExtractionPlanService.plan(List.of(a, b), false, text, null,
                "s", "in", "out", false, false);
        assertTrue(pathsOf.apply(all).containsAll(List.of(a.path, b.path)));

        var checked = ExtractionPlanService.plan(List.of(a, b), false, text, null,
                "s", "in", "out", false, true);
        assertTrue(pathsOf.apply(checked).contains(a.path));
        assertFalse(pathsOf.apply(checked).contains(b.path));
    }

    /** 与规划器直出的单元结构逐项一致（输出名/路径/成员），唯一差异 = 运行时字段补充。 */
    @Test
    void planMatchesPlannerOutputWithSupplementApplied() {
        PreviewItem a = item("C:/x/a.mht", "C:/x", "a.mht");
        PreviewItem b = item("C:/y/b.mht", "C:/y", "b.mht");
        a.selected = true;
        b.selected = false;
        AppConfig text = new AppConfig();
        text.setSeparateFiles(false);

        var expected = OutputUnitPlanner.plan(List.of(a, b), ExtractionPlanService.itemView(true),
                false, text, null, "session");
        var actual = ExtractionPlanService.plan(List.of(a, b), false, text, null, "session",
                "in", "out", true, true);
        assertEquals(expected.size(), actual.size());
        for (int i = 0; i < expected.size(); i++) {
            var e = expected.get(i);
            var u = actual.get(i);
            assertEquals(e.getOutputName(), u.getOutputName());
            assertEquals(e.getOutputPath(), u.getOutputPath());
            assertEquals(e.getPaths(), u.getPaths());
            assertEquals(e.fileCount(), u.fileCount());
            AppConfig p = u.getParams();
            assertEquals("in", p.getInputFolder());
            assertEquals("out", p.getOutputFolder());
            assertFalse(p.isImageModeEnabled());
            assertTrue(p.isOpenFolderAfterComplete());
        }
    }

    // ==================== overrides ====================

    /** 每文件覆盖映射（P3-3b-9）：按传入顺序落 LinkedHashMap、键 = item.path、
     * 值 = chapterEnabled / titleOverride 两字段（与搬迁前 gui 侧 overrides() 同构）。 */
    @Test
    void overridesBuildsPerFileMapInInsertionOrder() {
        PreviewItem a = item("C:/x/a.mht", "C:/x", "a.mht");
        PreviewItem b = item("C:/y/b.mht", "C:/y", "b.mht");
        a.chapterEnabled = false;
        a.titleOverride = "自定义标题";
        b.chapterEnabled = true;
        b.titleOverride = null;
        var map = ExtractionPlanService.overrides(List.of(a, b));
        assertEquals(List.of("C:/x/a.mht", "C:/y/b.mht"), List.copyOf(map.keySet()));
        assertFalse(map.get("C:/x/a.mht").chapterEnabled());
        assertEquals("自定义标题", map.get("C:/x/a.mht").titleOverride());
        assertTrue(map.get("C:/y/b.mht").chapterEnabled());
        assertNull(map.get("C:/y/b.mht").titleOverride());
    }

    // ==================== itemView ====================

    /** 基础视图 checked 恒 true；勾选视图 checked = 条目勾选；字段映射（路径/来源/移除标记/条目参数）两者一致。 */
    @Test
    void itemViewBaseCheckedAlwaysTrueCheckedViewFollowsSelection() {
        PreviewItem a = item("C:/x/a.mht", "C:/x", "a.mht");
        PreviewItem b = item("C:/x/b.mht", "C:/x", "b.mht");
        a.selected = true;
        b.selected = false;
        AppConfig p = new AppConfig();
        a.textItemParams = p;

        var base = ExtractionPlanService.itemView(false);
        assertTrue(base.checked(a));
        assertTrue(base.checked(b));
        assertEquals("C:/x/a.mht", base.path(a));
        assertEquals("C:/x", base.sourceFolder(a));
        assertFalse(base.removed(a));
        assertSame(p, base.textItemParams(a));
        assertNull(base.imageItemParams(a));

        var checked = ExtractionPlanService.itemView(true);
        assertTrue(checked.checked(a));
        assertFalse(checked.checked(b));
        assertEquals("C:/x/a.mht", checked.path(a));
        assertEquals("C:/x", checked.sourceFolder(a));
        assertFalse(checked.removed(a));
        assertSame(p, checked.textItemParams(a));
        assertNull(checked.imageItemParams(a));
    }
}
