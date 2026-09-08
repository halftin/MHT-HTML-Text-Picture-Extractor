package com.mhtextractor.util;

import com.mhtextractor.config.AppConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 参数作用域纯逻辑测试（F5 阶段 5）：目标路由、弹窗初值、生效参数覆盖层、背景色三态。
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

    // ==================== 生效参数（6.5 覆盖层） ====================

    @Test
    void effectiveParamsTopLevelUsesGlobal() {
        AppConfig global = cfg();
        assertSame(global, ParamScope.effectiveParams(null, false, global, null));
        assertNull(ParamScope.effectiveParams(null, false, null, null));
    }

    @Test
    void effectiveParamsGroupOverridesGlobal() {
        GroupItem<String> g = group(1, "组1");
        AppConfig groupCfg = cfg();
        g.setTextParamsCache(groupCfg);
        assertSame(groupCfg, ParamScope.effectiveParams(g, false, cfg(), null));
    }

    @Test
    void effectiveParamsGroupUnsetInheritsGlobal() {
        GroupItem<String> g = group(1, "组1");
        AppConfig global = cfg();
        assertSame(global, ParamScope.effectiveParams(g, false, global, null));
        assertNull(ParamScope.effectiveParams(g, false, null, null));
    }

    @Test
    void effectiveParamsModeIsolated() {
        GroupItem<String> g = group(1, "组1");
        AppConfig textCfg = cfg();
        g.setTextParamsCache(textCfg);
        AppConfig imageCfg = cfg();
        g.setImageParamsCache(imageCfg);
        assertSame(textCfg, ParamScope.effectiveParams(g, false, null, null));
        assertSame(imageCfg, ParamScope.effectiveParams(g, true, null, null));
    }

    // ==================== 背景色三态（8.5） ====================

    @Test
    void backgroundStatusNoEffectiveParams() {
        GroupItem<String> g = group(1, "组1");
        // 顶层项目且全局未确认；组内成员且组未设置、全局也未确认
        assertEquals(ParamScope.Status.NONE, ParamScope.backgroundStatus(null, false, null, null));
        assertEquals(ParamScope.Status.NONE, ParamScope.backgroundStatus(g, false, null, null));
    }

    @Test
    void backgroundStatusEffectiveIsGlobal() {
        GroupItem<String> g = group(1, "组1");
        AppConfig global = cfg();
        // 顶层项目且全局已确认；组内成员继承全局
        assertEquals(ParamScope.Status.GLOBAL,
                ParamScope.backgroundStatus(null, false, global, null));
        assertEquals(ParamScope.Status.GLOBAL, ParamScope.backgroundStatus(g, false, global, null));
    }

    @Test
    void backgroundStatusEffectiveIsGroup() {
        GroupItem<String> g = group(1, "组1");
        g.setTextParamsCache(cfg());
        // 组参数已设置 → 深绿（与全局是否确认无关）
        assertEquals(ParamScope.Status.GROUP, ParamScope.backgroundStatus(g, false, null, null));
        assertEquals(ParamScope.Status.GROUP, ParamScope.backgroundStatus(g, false, cfg(), null));
    }

    @Test
    void backgroundStatusModeIsolated() {
        GroupItem<String> g = group(1, "组1");
        g.setImageParamsCache(cfg());
        // 组仅设置图片参数：图片模式深绿；文本模式按全局（未确认 → 现状色）
        assertEquals(ParamScope.Status.GROUP, ParamScope.backgroundStatus(g, true, null, null));
        assertEquals(ParamScope.Status.NONE, ParamScope.backgroundStatus(g, false, null, null));
    }
}
