package com.mhtextractor.gui.component;

import com.mhtextractor.model.AppConfig;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uicore.ParamTargetText;
import com.mhtextractor.model.ParamScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标信息栏（R1.4）的<b>纯文案层</b>单测：只测 {@link ParamTargetText}（零 JavaFX 的静态函数），
 * 不构造控件 —— 因为 {@link ParamTargetBar} 是 {@code HBox}，无头测试构造不了它。
 *
 * <p>覆盖：三类目标的文本、括号来自既有键、条目名的两种来源、组内混合提示的追加、
 * 以及 4 个新键在两份 bundle 里都存在且渲染后不含 {@code !}。</p>
 */
class ParamTargetTextTest {

    @AfterEach
    void resetLocale() {
        I18n.setLocale(null);
    }

    private static PreviewItem item(String path, String name) {
        return new PreviewItem(path, name, LocalDateTime.now(), "C:\\src");
    }

    // ==================== 三类目标 ====================

    @Test
    void globalTargetShowsGlobalLabel() {
        I18n.setLocale(new Locale("zh", "CN"));
        assertEquals("全局", ParamTargetText.forTarget(ParamScope.GLOBAL, null, false));
        I18n.setLocale(new Locale("en", "US"));
        assertEquals("Global", ParamTargetText.forTarget(ParamScope.GLOBAL, null, false));
    }

    @Test
    void groupTargetShowsGroupNameInsideExistingParenKeys() {
        I18n.setLocale(new Locale("zh", "CN"));
        GroupItem<PreviewItem> group = new GroupItem<>(1, "组1");
        String text = ParamTargetText.forTarget(new ParamScope.GroupTarget(group), null, false);

        assertEquals("组（组1）", text);
        assertFalse(text.contains("!"), "新键不得缺键：" + text);
        // 括号必须来自既有键（不新增括号键、不在 Java 里写死全角括号）
        assertEquals("组" + I18n.get("dialog.parenOpen") + "组1" + I18n.get("dialog.parenClose"), text);

        I18n.setLocale(new Locale("en", "US"));
        assertEquals("Group (组1)", ParamTargetText.forTarget(new ParamScope.GroupTarget(group), null, false),
                "英文排版（括号前留空格）由 bundle 的模板决定，Java 不写标点");
    }

    @Test
    void itemTargetUsesProvidedDisplayNameFirst() {
        I18n.setLocale(new Locale("zh", "CN"));
        PreviewItem it = item("C:\\src\\real.mht", "real.mht");
        String text = ParamTargetText.forTarget(
                new ParamScope.ItemTarget<>(it, null, null), "覆盖名.mht", false);
        assertEquals("条目（覆盖名.mht）", text, "调用方给了显示名就用它");
    }

    @Test
    void itemTargetResolvesPreviewItemNameThenPathBaseName() {
        I18n.setLocale(new Locale("zh", "CN"));

        PreviewItem named = item("C:\\src\\a.mht", "a.mht");
        assertEquals("条目（a.mht）",
                ParamTargetText.forTarget(new ParamScope.ItemTarget<>(named, null, null), null, false));

        // name 为空 → 回退到 path 末段（纯字符串切分，不碰 Path.of，避免非法路径字符抛异常）
        PreviewItem blankName = item("C:\\src\\sub\\b.mht", "   ");
        assertEquals("条目（b.mht）",
                ParamTargetText.forTarget(new ParamScope.ItemTarget<>(blankName, null, null), null, false));
    }

    // ==================== 组内混合提示（R1.4 增强项） ====================

    @Test
    void groupMixedHintIsAppendedOnlyWhenMixed() {
        I18n.setLocale(new Locale("zh", "CN"));
        GroupItem<PreviewItem> group = new GroupItem<>(1, "组1");
        ParamScope.Target target = new ParamScope.GroupTarget(group);

        String clean = ParamTargetText.forTarget(target, null, false);
        String mixed = ParamTargetText.forTarget(target, null, true);

        assertNotEquals(clean, mixed);
        assertTrue(mixed.startsWith(clean), "混合提示是「追加」语义，不改变原有目标文案：" + mixed);
        assertTrue(mixed.contains(I18n.get("paramTarget.groupMixed")), "混合提示文案来自 paramTarget.groupMixed");
        assertEquals("组（组1） · 组内存在多种参数", mixed);

        I18n.setLocale(new Locale("en", "US"));
        assertEquals("Group (组1) · multiple parameter sets in this group",
                ParamTargetText.forTarget(target, null, true));
    }

    @Test
    void isGroupMixedOnlyForGroupTargetsAndUsesExplicitMemberQuery() {
        try (var root = new com.mhtextractor.engine.BackendWorkspace(request -> {
            throw new AssertionError("Unexpected confirmation");
        }, AppConfig::new, Runnable::run);
             var frontend = new com.mhtextractor.uicore.FrontendPort(root.port(), Runnable::run, ignored -> {})) {
            var commands = root.port().commands();
            String withOwn = java.nio.file.Path.of("H:/TEST/mht/a.mht").toString();
            String without = java.nio.file.Path.of("H:/TEST/mht/b.mht").toString();
            commands.applyImportPlan(new com.mhtextractor.uiport.ImportPlanVm(List.of(
                    new com.mhtextractor.uiport.ImportPlanVm.ImportBatchVm("H:/TEST/mht", "group", true,
                            List.of(withOwn, without))), 0, 0, 0, 0, 0, false, false));
            AppConfig itemParams = new AppConfig();
            itemParams.setStartMarker("ITEM");
            commands.setItemParams(withOwn, false, itemParams);
            frontend.bootstrap(new AppConfig());
            var view = new com.mhtextractor.uicore.WorkspaceView(frontend, no -> "Group " + no);
            var group = view.groups().getFirst();
            var target = new com.mhtextractor.uicore.ParamScope.GroupTarget(group);
            assertTrue(ParamTargetText.isGroupMixed(target, root.port().queries(), false),
                    "一个成员有条目级参数、另一个没有 ⇒ 组内不统一");
            assertTrue(ParamTargetText.isGroupMixed(target, frontend.queries(), false));
            commands.setGroupParams(group.getGroupNo(), false, new AppConfig());
            assertFalse(ParamTargetText.isGroupMixed(target, root.port().queries(), false),
                    "组参数已设置 ⇒ 组内统一");
            assertFalse(ParamTargetText.isGroupMixed(com.mhtextractor.uicore.ParamScope.GLOBAL,
                    root.port().queries(), false), "全局目标不算混合");
            assertFalse(ParamTargetText.isGroupMixed(new com.mhtextractor.uicore.ParamScope.ItemTarget<>(
                    group.getMembers().getFirst(), null, null), root.port().queries(), false), "条目目标不算混合");
            view.dispose();
        }
    }

    // ==================== i18n：4 个新键在两份 bundle 都在 ====================

    @Test
    void newKeysExistInBothBundlesAndRenderWithoutBang() {
        List<String> keys = List.of("paramTarget.global", "paramTarget.group",
                "paramTarget.item", "paramTarget.groupMixed");
        for (Locale loc : List.of(new Locale("zh", "CN"), new Locale("en", "US"))) {
            I18n.setLocale(loc);
            for (String key : keys) {
                // 多给的实参对无占位符的键是无害的（render 只替换模板里出现的名字）
                String rendered = I18n.get(key, "name", "X");
                assertFalse(rendered.equals("!" + key + "!"), "[" + loc + "] 缺键：" + key);
                assertFalse(rendered.isBlank(), "[" + loc + "] 键渲染为空：" + key);
                assertFalse(rendered.contains("{"), "[" + loc + "] 键含未替换占位符：" + key + " -> " + rendered);
            }
        }
    }
}
