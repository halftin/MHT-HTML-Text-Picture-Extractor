package com.mhtextractor.util;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.model.GroupItem;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数作用域纯逻辑测试（F5 阶段 5 + 条目独立参数需求）：目标路由、弹窗初值、
 * 生效参数三层覆盖（组 &gt; 条目 &gt; 全局）、背景色三态。
 * 用 String 模拟项目对象，无需 JavaFX 运行时（GroupItem 属性为纯 JavaFX beans 属性，可直接实例化）。
 */
class ParamScopeTest {

    private static GroupItem<String> group(int no, String name) {
        return new GroupItem<>(no, name);
    }

    private static AppConfig cfg() {
        return new AppConfig();
    }

    // ==================== 目标路由（8.1 修订：只认选中项，成员 → 所在组） ====================

    @Test
    void routeGroupItemToGroupTarget() {
        GroupItem<String> g = group(1, "组1");
        ParamScope.Target t = ParamScope.route(g, null);
        assertTrue(t instanceof ParamScope.GroupTarget gt && gt.group() == g);
    }

    @Test
    void routeGroupedMemberToItsGroupTarget() {
        GroupItem<String> g = group(1, "组1");
        ParamScope.Target t = ParamScope.route("组内成员", g);
        assertTrue(t instanceof ParamScope.GroupTarget gt && gt.group() == g);
    }

    @Test
    void routeTopLevelItemOrNothingToGlobalTarget() {
        assertTrue(ParamScope.route("顶层项目", null) instanceof ParamScope.GlobalTarget);
        assertTrue(ParamScope.route(null, null) instanceof ParamScope.GlobalTarget);
        assertSame(ParamScope.GLOBAL, ParamScope.route(null, null));
    }

    // ==================== 弹窗初值 ====================

    @Test
    void initialParamsPrefersGroupOwn() {
        GroupItem<String> g = group(1, "组1");
        AppConfig groupCfg = cfg();
        groupCfg.setStartMarker("GROUP");
        g.setTextParamsCache(groupCfg);
        AppConfig global = cfg();
        global.setStartMarker("GLOBAL");
        assertSame(groupCfg,
                ParamScope.initialParams(new ParamScope.GroupTarget(g), false, global, null));
    }

    @Test
    void initialParamsFallsBackToGlobalWhenGroupUnset() {
        GroupItem<String> g = group(1, "组1");
        AppConfig global = cfg();
        assertSame(global,
                ParamScope.initialParams(new ParamScope.GroupTarget(g), false, global, null));
    }

    @Test
    void initialParamsNullWhenNothingConfirmed() {
        GroupItem<String> g = group(1, "组1");
        assertNull(ParamScope.initialParams(new ParamScope.GroupTarget(g), false, null, null));
        assertNull(ParamScope.initialParams(ParamScope.GLOBAL, false, null, null));
    }

    @Test
    void initialParamsGlobalTargetReadsGlobalCache() {
        AppConfig textGlobal = cfg();
        AppConfig imageGlobal = cfg();
        assertSame(textGlobal,
                ParamScope.initialParams(ParamScope.GLOBAL, false, textGlobal, imageGlobal));
        assertSame(imageGlobal,
                ParamScope.initialParams(ParamScope.GLOBAL, true, textGlobal, imageGlobal));
    }

    @Test
    void initialParamsItemTargetReadsItemParamsWithGlobalFallback() {
        AppConfig itemCfg = cfg();
        AppConfig global = cfg();
        ParamScope.ItemTarget<String> t = new ParamScope.ItemTarget<>("条目", itemCfg, null);
        assertSame(itemCfg, ParamScope.initialParams(t, false, global, null));
        assertSame(global, ParamScope.initialParams(t, true, null, global));
        assertNull(ParamScope.initialParams(new ParamScope.ItemTarget<>("条目", null, null),
                false, null, null));
    }

    // ==================== 生效参数（三层覆盖：组 > 条目 > 全局） ====================

    @Test
    void effectiveParamsTopLevelUsesGlobal() {
        AppConfig global = cfg();
        assertSame(global, ParamScope.effectiveParams(null, null, null, false, global, null));
        assertNull(ParamScope.effectiveParams(null, null, null, false, null, null));
    }

    @Test
    void effectiveParamsItemOverridesGlobal() {
        AppConfig global = cfg();
        AppConfig itemCfg = cfg();
        assertSame(itemCfg, ParamScope.effectiveParams(null, itemCfg, null, false, global, null));
        assertSame(itemCfg, ParamScope.effectiveParams(null, null, itemCfg, true, null, global));
    }

    @Test
    void effectiveParamsGroupBeatsItemAndGlobal() {
        GroupItem<String> g = group(1, "组1");
        AppConfig groupCfg = cfg();
        g.setTextParamsCache(groupCfg);
        AppConfig itemCfg = cfg();
        AppConfig global = cfg();
        assertSame(groupCfg, ParamScope.effectiveParams(g, itemCfg, null, false, global, null));
    }

    @Test
    void effectiveParamsGroupOverridesGlobal() {
        GroupItem<String> g = group(1, "组1");
        AppConfig groupCfg = cfg();
        g.setTextParamsCache(groupCfg);
        assertSame(groupCfg, ParamScope.effectiveParams(g, null, null, false, cfg(), null));
    }

    @Test
    void effectiveParamsGroupUnsetInheritsGlobal() {
        GroupItem<String> g = group(1, "组1");
        AppConfig global = cfg();
        assertSame(global, ParamScope.effectiveParams(g, null, null, false, global, null));
        assertNull(ParamScope.effectiveParams(g, null, null, false, null, null));
    }

    @Test
    void effectiveParamsModeIsolated() {
        GroupItem<String> g = group(1, "组1");
        AppConfig textCfg = cfg();
        g.setTextParamsCache(textCfg);
        AppConfig imageCfg = cfg();
        g.setImageParamsCache(imageCfg);
        assertSame(textCfg, ParamScope.effectiveParams(g, null, null, false, null, null));
        assertSame(imageCfg, ParamScope.effectiveParams(g, null, null, true, null, null));
    }

    @Test
    void effectiveParamsItemModeIsolated() {
        AppConfig textCfg = cfg();
        AppConfig imageCfg = cfg();
        assertSame(textCfg, ParamScope.effectiveParams(null, textCfg, imageCfg, false, null, null));
        assertSame(imageCfg, ParamScope.effectiveParams(null, textCfg, imageCfg, true, null, null));
    }

    // ==================== 背景色三态（8.5 + 条目独立参数） ====================

    @Test
    void backgroundStatusNoEffectiveParams() {
        GroupItem<String> g = group(1, "组1");
        // 顶层项目且全局未确认；组内成员且组未设置、全局也未确认
        assertEquals(ParamScope.Status.NONE,
                ParamScope.backgroundStatus(null, null, null, false, null, null));
        assertEquals(ParamScope.Status.NONE,
                ParamScope.backgroundStatus(g, null, null, false, null, null));
    }

    @Test
    void backgroundStatusEffectiveIsGlobal() {
        GroupItem<String> g = group(1, "组1");
        AppConfig global = cfg();
        // 顶层项目且全局已确认；组内成员继承全局
        assertEquals(ParamScope.Status.GLOBAL,
                ParamScope.backgroundStatus(null, null, null, false, global, null));
        assertEquals(ParamScope.Status.GLOBAL,
                ParamScope.backgroundStatus(g, null, null, false, global, null));
    }

    @Test
    void backgroundStatusEffectiveIsGroup() {
        GroupItem<String> g = group(1, "组1");
        g.setTextParamsCache(cfg());
        // 组参数已设置 → 浅蓝（与全局是否确认无关）
        assertEquals(ParamScope.Status.GROUP,
                ParamScope.backgroundStatus(g, null, null, false, null, null));
        assertEquals(ParamScope.Status.GROUP,
                ParamScope.backgroundStatus(g, null, null, false, cfg(), null));
    }

    @Test
    void backgroundStatusEffectiveIsItem() {
        // 条目级参数已设置 → 浅蓝（与全局是否确认无关）
        assertEquals(ParamScope.Status.GROUP,
                ParamScope.backgroundStatus(null, cfg(), null, false, null, null));
        assertEquals(ParamScope.Status.GROUP,
                ParamScope.backgroundStatus(null, null, cfg(), true, null, null));
        // 模式隔离：仅文本条目参数，图片模式无 → 现状色
        assertEquals(ParamScope.Status.NONE,
                ParamScope.backgroundStatus(null, cfg(), null, true, null, null));
    }

    @Test
    void backgroundStatusModeIsolated() {
        GroupItem<String> g = group(1, "组1");
        g.setImageParamsCache(cfg());
        // 组仅设置图片参数：图片模式浅蓝；文本模式按全局（未确认 → 现状色）
        assertEquals(ParamScope.Status.GROUP,
                ParamScope.backgroundStatus(g, null, null, true, null, null));
        assertEquals(ParamScope.Status.NONE,
                ParamScope.backgroundStatus(g, null, null, false, null, null));
    }

    // ==================== 会话 7：图片模式唯一入口 effectiveImageParams ====================

    @Test
    void effectiveImageParamsGroupBeatsItemAndGlobal() {
        GroupItem<String> g = group(1, "组1");
        AppConfig groupCfg = cfg();
        g.setImageParamsCache(groupCfg);
        assertSame(groupCfg, ParamScope.effectiveImageParams(g, cfg(), cfg()));
    }

    @Test
    void effectiveImageParamsItemBeatsGlobal() {
        AppConfig itemCfg = cfg();
        assertSame(itemCfg, ParamScope.effectiveImageParams(null, itemCfg, cfg()));
    }

    @Test
    void effectiveImageParamsFallsBackToGlobal() {
        AppConfig global = cfg();
        assertSame(global, ParamScope.effectiveImageParams(null, null, global));
        assertSame(global, ParamScope.effectiveImageParams(group(1, "组1"), null, global));
    }

    @Test
    void effectiveImageParamsNullWhenNothingConfirmed() {
        assertNull(ParamScope.effectiveImageParams(null, null, null));
        assertNull(ParamScope.effectiveImageParams(group(1, "组1"), null, null));
    }

    @Test
    void effectiveImageParamsTextParamsNeverLeakIn() {
        GroupItem<String> g = group(1, "组1");
        g.setTextParamsCache(cfg());
        AppConfig global = cfg();
        // 组仅确认文本参数 → 图片模式回退全局
        assertSame(global, ParamScope.effectiveImageParams(g, null, global));
    }

    /**
     * 等价性锁定（会话 7）：提取路径（OutputUnitPlanner.resolve 经 effectiveParams 委托）
     * 与预览路径（MainController.effectiveForObject 直接调用）必须解析出同一实例。
     * 组合矩阵：组（无/未确认/已确认）× 条目（无/有）× 全局（未确认/已确认）。
     * MainController 无法在纯单元测试中实例化（JavaFX 控件依赖），其包装逻辑仅 4 行且直接转发
     * 本入口（见 docs/handoff/H7.md），故以唯一入口 + 委托等价性锁定两条路径一致；
     * assertSame（同实例）强于逐字段相等。
     */
    @Test
    void effectiveImageParamsMatchesLegacyPathForAllCombos() {
        GroupItem<String> groupUnset = group(1, "组未确认");
        GroupItem<String> groupSet = group(2, "组已确认");
        AppConfig groupCfg = cfg();
        groupSet.setImageParamsCache(groupCfg);
        AppConfig itemCfg = cfg();
        AppConfig global = cfg();
        // 干扰项：文本参数不得影响图片模式解析
        AppConfig textItem = cfg();
        AppConfig textGlobal = cfg();

        List<GroupItem<String>> parents = Arrays.asList(null, groupUnset, groupSet);
        List<AppConfig> items = Arrays.asList(null, itemCfg);
        List<AppConfig> globals = Arrays.asList(null, global);
        for (GroupItem<String> parent : parents) {
            for (AppConfig item : items) {
                for (AppConfig gl : globals) {
                    AppConfig viaEntry = ParamScope.effectiveImageParams(parent, item, gl);
                    AppConfig viaParams = ParamScope.effectiveParams(parent, textItem, item, true, textGlobal, gl);
                    assertSame(viaEntry, viaParams,
                            "预览/提取两条路径解析结果必须为同一实例: parent=" + parent + " item=" + item);
                    AppConfig expected = (parent != null && parent.getImageParamsCache() != null)
                            ? parent.getImageParamsCache() : (item != null ? item : gl);
                    assertSame(expected, viaEntry);
                }
            }
        }
    }
}
