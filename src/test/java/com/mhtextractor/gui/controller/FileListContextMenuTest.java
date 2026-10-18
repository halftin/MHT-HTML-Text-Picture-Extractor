package com.mhtextractor.gui.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import com.mhtextractor.app.EffectiveParamsService;
import com.mhtextractor.uicore.ParamsInfo;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uicore.FileMenuModel;

import javafx.scene.control.MenuItem;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

/**
 * {@link FileListContextMenu} 的纯逻辑测试（Q9a / P4b-①；Q10 增补 R7.1-R7.3/R7.6）。
 *
 * <p><b>为什么全部无头</b>：本类把"启用规则 / 顺序 / 文案键"做成了不依赖 JavaFX 构造的纯数据与纯函数
 * （构造器只建 {@code MenuItem}——实测无 toolkit 也能建），于是这些规则可以被单测直接钉死，
 * 不必启动 GUI。</p>
 *
 * <p><b>为什么不构造真实菜单</b>（实测，2026-10-03）：无头 JVM 里 {@code new SeparatorMenuItem()} /
 * {@code new ContextMenu()} 会以 {@code IllegalStateException: Toolkit not initialized} 失败；而
 * {@code WorkspaceFxAdapterTest} 末尾调了 {@code Platform.exit()}，之后的用例 {@code Platform.startup}
 * 必抛 {@code IllegalStateException: Platform.exit has been called} ⇒ "在单测里建真实菜单"会变成
 * <b>顺序依赖式假失败</b>。因此真实 {@code ContextMenu.getItems()} 的顺序与两项新菜单项的真实文案/禁用
 * 由 GUI 探针 {@code tools/probes/ContextMenuProbe.java} 独立验证，这里只钉纯数据与纯函数。</p>
 */
class FileListContextMenuTest {

    // ==================== 1. 恒 18 项同序（14 项 + 4 分隔符） ====================

    /**
     * 顺序与分隔符位置：期望值在这里<b>逐项写死</b>（不复用 {@code LAYOUT}），因此它是一份独立副本 ——
     * {@code LAYOUT} 被改动时本断言必失败。分隔符位置 = 搬前 {@code MainController:1200-1209} 的分组
     * 边界 + Q10/R7.1 在第 3 组末尾追加的两项（故 10/13 → 12/15）；
     * ⚠️ {@code OPEN_IN_BROWSER} 必须排在 {@code OPEN_FOLDER} 之前。
     */
    @Test
    void layoutIsExactlyTheExpectedEighteenEntriesInOrder() {
        List<FileMenuModel.Entry> expected = List.of(
                FileMenuModel.Entry.SELECT,
                FileMenuModel.Entry.SELECT_ALL,
                FileMenuModel.Entry.SEPARATOR_1,
                FileMenuModel.Entry.OPEN_IN_BROWSER,
                FileMenuModel.Entry.OPEN_FOLDER,
                FileMenuModel.Entry.VIEW_SOURCE,
                FileMenuModel.Entry.SEPARATOR_2,
                FileMenuModel.Entry.PARAMS,
                FileMenuModel.Entry.CLEAR_PARAMS,
                FileMenuModel.Entry.APPLY_GLOBAL,
                FileMenuModel.Entry.COPY_PARAMS,
                FileMenuModel.Entry.APPLY_TO_SELECTION,
                FileMenuModel.Entry.SEPARATOR_3,
                FileMenuModel.Entry.GROUP,
                FileMenuModel.Entry.UNGROUP,
                FileMenuModel.Entry.SEPARATOR_4,
                FileMenuModel.Entry.REMOVE,
                FileMenuModel.Entry.REMOVE_GROUP);

        assertEquals(18, FileMenuModel.LAYOUT.size(), "菜单恒 18 项（14 项 + 4 分隔符）");
        assertEquals(expected, FileMenuModel.LAYOUT, "菜单项顺序必须与硬编码期望逐项一致");

        int separators = 0;
        for (int i = 0; i < FileMenuModel.LAYOUT.size(); i++) {
            FileMenuModel.Entry entry = FileMenuModel.LAYOUT.get(i);
            if (entry.isSeparator()) {
                separators++;
                assertTrue(entry.name().startsWith("SEPARATOR_"), "分隔符条目应命名为 SEPARATOR_*：" + entry);
            }
        }
        assertEquals(4, separators, "分隔符恒 4 个（Q10 不新增分隔符）");
        // 分组边界（0-based 下标）—— 分隔符位置钉死，防"顺序对但分组挪了"
        assertEquals(List.of(2, 6, 12, 15),
                List.of(FileMenuModel.Entry.SEPARATOR_1, FileMenuModel.Entry.SEPARATOR_2,
                        FileMenuModel.Entry.SEPARATOR_3, FileMenuModel.Entry.SEPARATOR_4).stream()
                        .map(FileMenuModel.LAYOUT::indexOf).toList(),
                "4 个分隔符的下标必须是 2 / 6 / 12 / 15");
        // R7.1：两项新菜单项紧贴既有参数三项同组（第 3 组 = 下标 7..11）
        assertEquals(List.of(FileMenuModel.Entry.PARAMS, FileMenuModel.Entry.CLEAR_PARAMS,
                        FileMenuModel.Entry.APPLY_GLOBAL, FileMenuModel.Entry.COPY_PARAMS,
                        FileMenuModel.Entry.APPLY_TO_SELECTION),
                FileMenuModel.LAYOUT.subList(7, 12), "第 3 组 = 参数五项（原三项 + 复制参数 + 应用到所选）");
    }

    // ==================== 2. 文案键逐字不变（Q10 新增 4 个键） ====================

    @Test
    void textKeysMatchTheExpectedFourteenKeys() {
        assertEquals(14, FileMenuModel.TEXT_KEYS.size(), "只有 14 个菜单项有文案键");
        assertEquals("list.menuSelect", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.SELECT));
        assertEquals("list.menuSelectAll", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.SELECT_ALL));
        assertEquals("list.menuOpenFolder", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.OPEN_FOLDER));
        assertEquals("browser.menuOpen", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.OPEN_IN_BROWSER));
        assertEquals("browser.menuViewSource", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.VIEW_SOURCE));
        assertEquals("params.menuSet", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.PARAMS));
        assertEquals("params.menuClear", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.CLEAR_PARAMS));
        assertEquals("params.menuApplyGlobal", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.APPLY_GLOBAL));
        assertEquals("group.menuMerge", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.GROUP));
        assertEquals("group.menuUngroup", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.UNGROUP));
        assertEquals("list.menuRemove", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.REMOVE));
        assertEquals("group.menuRemove", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.REMOVE_GROUP));
        // R7.1/R7.2：两项新菜单项的键（复制参数按状态在三个键之间切换）
        assertEquals("params.menuCopy", FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.COPY_PARAMS));
        // 功能二：应用到所选改为 5 档动态文案；TEXT_KEYS 里的静态默认值 = 第一档（"到此项"）
        assertEquals("params.menuApplyToThisItem",
                FileMenuModel.TEXT_KEYS.get(FileMenuModel.Entry.APPLY_TO_SELECTION));
        assertEquals(List.of("params.menuApplyToThisItem", "params.menuApplyToManyItems",
                        "params.menuApplyToGroup", "params.menuApplyToGroupAndThisItem",
                        "params.menuApplyToGroupAndMany"),
                FileMenuModel.APPLY_TO_SELECTION_KEYS, "五档文案键逐字固定");
        assertEquals(FileMenuModel.KEY_APPLY_THIS, FileMenuModel.APPLY_TO_SELECTION_KEYS.get(0));
        assertEquals(FileMenuModel.KEY_APPLY_MANY, FileMenuModel.APPLY_TO_SELECTION_KEYS.get(1));
        assertEquals(FileMenuModel.KEY_APPLY_GROUP, FileMenuModel.APPLY_TO_SELECTION_KEYS.get(2));
        assertEquals(FileMenuModel.KEY_APPLY_GROUP_AND_THIS, FileMenuModel.APPLY_TO_SELECTION_KEYS.get(3));
        assertEquals(FileMenuModel.KEY_APPLY_GROUP_AND_MANY, FileMenuModel.APPLY_TO_SELECTION_KEYS.get(4));
        assertEquals(5, FileMenuModel.APPLY_TO_SELECTION_KEYS.stream().distinct().count());
        assertEquals("params.menuCopy", FileMenuModel.KEY_COPY_PARAMS);
        assertEquals("params.menuCopyNoParams", FileMenuModel.KEY_COPY_NO_PARAMS);
        assertEquals("params.menuCopyMixedParams", FileMenuModel.KEY_COPY_MIXED_PARAMS);

        // 动态文案（空白区域 → 全局参数入口）
        assertEquals("params.menuSet", FileMenuModel.KEY_PARAMS);
        assertEquals("params.menuSetGlobal", FileMenuModel.KEY_PARAMS_GLOBAL);

        // 分隔符不得有文案键
        for (FileMenuModel.Entry entry : FileMenuModel.LAYOUT) {
            if (entry.isSeparator()) {
                assertNull(FileMenuModel.TEXT_KEYS.get(entry), "分隔符不应有文案键：" + entry);
            } else {
                assertNotNull(FileMenuModel.TEXT_KEYS.get(entry), "菜单项必须有文案键：" + entry);
            }
        }
    }

    // ==================== 3. 启用规则（四种目标 × 独立参数两种取值） ====================

    /** 参数判定快照：既有 13 位只看 {@code independent}；R7 两位在本方法里另有专门断言 */
    private static ParamsInfo info(boolean independent) {
        return new ParamsInfo(independent, false, true, false, 0, 0);
    }

    /**
     * §1.4 的规则逐条对账。{@code itemScoped} = 「打开所在文件夹 / 在浏览器中查看 / 查看源码 /
     * 建组 / 移除」这五个共用"是项目条目"的条件。
     */
    private static void assertMatrix(FileMenuModel.TargetType kind,
                                     boolean select,
                                     boolean itemScoped,
                                     boolean paramsGlobal,
                                     boolean group,
                                     boolean ungroup,
                                     boolean remove,
                                     boolean removeGroup) {
        for (boolean independent : new boolean[] {false, true}) {
            FileMenuModel.State s = FileMenuModel.stateFor(kind, info(independent));
            String what = kind + "（independent=" + independent + "）";
            assertEquals(select, s.select(), what + "：勾选");
            assertTrue(s.selectAll(), what + "：全选恒可用");
            assertEquals(itemScoped, s.openFolder(), what + "：打开所在文件夹");
            assertEquals(itemScoped, s.openInBrowser(), what + "：在浏览器中查看");
            assertEquals(itemScoped, s.viewSource(), what + "：查看源码");
            assertTrue(s.params(), what + "：设置参数恒可用");
            assertEquals(paramsGlobal, s.paramsGlobalText(), what + "：参数文案是否走全局入口");
            assertEquals(independent, s.clearParams(), what + "：清除参数");
            assertEquals(independent, s.applyGlobal(), what + "：应用到全局");
            assertEquals(itemScoped, s.group(), what + "：建组");
            assertEquals(ungroup, s.ungroup(), what + "：拆分组");
            assertEquals(itemScoped, s.remove(), what + "：移除");
            assertEquals(removeGroup, s.removeGroup(), what + "：移除组");
            // R7.2/R7.3：本组输入是"无生效参数、未复制过" ⇒ 两项都禁用，复制文案走"无参数"版本
            assertFalse(s.copyParams(), what + "：无生效参数 ⇒ 复制参数禁用");
            assertEquals(FileMenuModel.KEY_COPY_NO_PARAMS, s.copyParamsTextKey(), what + "：复制参数文案");
            assertFalse(s.applyToSelection(), what + "：未复制过 ⇒ 应用到所选禁用");
        }
    }

    @Test
    void enableRulesMatchTheOriginalMatrix() {
        // 空白区域：只有"全选 / 设置参数"可用，参数文案走全局入口
        assertMatrix(FileMenuModel.TargetType.BLANK, false, false, true, false, false, false, false);
        // 顶层条目：勾选 + 条目类动作 + 建组 + 移除；拆分组与移除组禁用
        assertMatrix(FileMenuModel.TargetType.TOP_ITEM, true, true, false, true, false, true, false);
        // 组内成员：与顶层条目同，但"移除组"可用
        assertMatrix(FileMenuModel.TargetType.MEMBER, true, true, false, true, false, true, true);
        // 组条目：勾选 + 拆分组 + 移除组；条目类动作、建组、移除禁用（组条目走"移除组"）
        assertMatrix(FileMenuModel.TargetType.GROUP, true, false, false, false, true, false, true);
    }

    /**
     * "非空但既不是条目也不是组"的目标：搬前 {@code updateContextMenuState} 的头四行会给出四个 false，
     * 与空白区域<b>不等价</b>（勾选项仍启用、参数文案仍是普通项）—— {@link FileMenuModel.TargetType}
     * 保留四个布尔而不是枚举，正是为了不改这个行为。
     */
    @Test
    void unknownNonNullTargetIsNotTreatedAsBlank() {
        FileMenuModel.TargetType unknown = new FileMenuModel.TargetType(false, false, false, false);
        FileMenuModel.State s = FileMenuModel.stateFor(unknown, info(false));
        assertTrue(s.select(), "非空目标：勾选项仍然启用（与空白区域不同）");
        assertNotEquals(FileMenuModel.stateFor(FileMenuModel.TargetType.BLANK, info(false)), s,
                "未知非空目标不得与空白区域等价");
        assertEquals(false, s.paramsGlobalText(), "非空目标：参数文案不是全局入口");
        assertEquals(false, s.group(), "非空目标：建组禁用");
        assertEquals(false, s.ungroup(), "非空目标：拆分组禁用");
        assertEquals(false, s.remove(), "非空目标：移除禁用");
        assertEquals(false, s.removeGroup(), "非空目标：移除组禁用");
    }

    // ==================== 3b. R7.2：两种禁用理由写在选项文本里 ====================

    /**
     * R7.2：无生效参数 → {@code params.menuCopyNoParams}；参数不统一 → {@code params.menuCopyMixedParams}；
     * 有生效参数且统一 → {@code params.menuCopy} 且可用。判定全部来自真实 {@link FileMenuModel#stateFor}。
     */
    @Test
    void copyItemTextSwitchesBetweenTheTwoDisabledReasonsAndTheEnabledText() {
        FileMenuModel.TargetType item = FileMenuModel.TargetType.TOP_ITEM;

        FileMenuModel.State none = FileMenuModel.stateFor(item,
                new ParamsInfo(false, false, true, false, 0, 0));
        assertFalse(none.copyParams(), "无生效参数 ⇒ 禁用");
        assertEquals(FileMenuModel.KEY_COPY_NO_PARAMS, none.copyParamsTextKey(), "理由一：无参数");

        FileMenuModel.State mixed = FileMenuModel.stateFor(item,
                new ParamsInfo(true, true, false, false, 0, 0));
        assertFalse(mixed.copyParams(), "参数不统一 ⇒ 禁用");
        assertEquals(FileMenuModel.KEY_COPY_MIXED_PARAMS, mixed.copyParamsTextKey(), "理由二：参数不统一");

        FileMenuModel.State ok = FileMenuModel.stateFor(item,
                new ParamsInfo(true, true, true, false, 0, 0));
        assertTrue(ok.copyParams(), "有生效参数且统一 ⇒ 可用");
        assertEquals(FileMenuModel.KEY_COPY_PARAMS, ok.copyParamsTextKey(), "可用时的文案");

        // 三个键在当前 locale 下必须两两不同，否则"文案切换"不可判
        assertEquals(3, List.of(I18n.get(FileMenuModel.KEY_COPY_PARAMS),
                        I18n.get(FileMenuModel.KEY_COPY_NO_PARAMS),
                        I18n.get(FileMenuModel.KEY_COPY_MIXED_PARAMS)).stream().distinct().count(),
                "复制参数的三个文案键渲染后必须两两不同");
    }

    // ==================== 3c. R7.3：「应用到所选」的前置 ====================

    @Test
    void applyToSelectionIsDisabledUntilSomethingWasCopied() {
        FileMenuModel.TargetType item = FileMenuModel.TargetType.TOP_ITEM;
        assertFalse(FileMenuModel.stateFor(item,
                new ParamsInfo(true, true, true, false, 0, 0)).applyToSelection(),
                "未复制过 ⇒ 不可交互");
        assertTrue(FileMenuModel.stateFor(item,
                new ParamsInfo(true, true, true, true, 0, 0)).applyToSelection(),
                "复制过 ⇒ 可用");
        // 与"是否有参数"无关：无参数目标 + 已复制过 ⇒ 仍可用（应用是写，不要求目标自己有参数）
        assertTrue(FileMenuModel.stateFor(item,
                new ParamsInfo(false, false, true, true, 0, 0)).applyToSelection(),
                "已复制过 ⇒ 可用（与目标自身是否有参数无关）");
        // 清空剪贴板 → 回到禁用（ParamClipboard.clear 的语义在下一条用例里钉住）
        assertFalse(FileMenuModel.stateFor(item,
                new ParamsInfo(true, true, true, false, 0, 0)).applyToSelection(),
                "清空（copied=false）⇒ 回到禁用");
    }

    // ==================== 3c-2. 功能二：5 档文案按实际作用集合分类 ====================

    /**
     * 文案例真值表逐行：只有组外项 1/≥2、只有组相关、组外 1/≥2 + 组、空集（保持默认档不新增）。
     * 直接驱动真实 {@link FileMenuModel#applyToSelectionTextKey(int, int)} 与
     * {@link FileMenuModel#stateFor}。
     */
    @Test
    void applyToSelectionTextKeyFollowsTheActualTargetSet() {
        assertEquals(FileMenuModel.KEY_APPLY_THIS,
                FileMenuModel.applyToSelectionTextKey(1, 0), "只有组外项 1 个 → 到此项");
        assertEquals(FileMenuModel.KEY_APPLY_MANY,
                FileMenuModel.applyToSelectionTextKey(2, 0), "只有组外项 2 个 → 到多项");
        assertEquals(FileMenuModel.KEY_APPLY_MANY,
                FileMenuModel.applyToSelectionTextKey(7, 0), "只有组外项 ≥2 → 到多项");
        assertEquals(FileMenuModel.KEY_APPLY_GROUP,
                FileMenuModel.applyToSelectionTextKey(0, 1), "只有组相关 → 到组");
        assertEquals(FileMenuModel.KEY_APPLY_GROUP,
                FileMenuModel.applyToSelectionTextKey(0, 3), "任意多个组、无组外项 → 到组");
        assertEquals(FileMenuModel.KEY_APPLY_GROUP_AND_THIS,
                FileMenuModel.applyToSelectionTextKey(1, 1), "组外 1 + 组 → 到组及此项");
        assertEquals(FileMenuModel.KEY_APPLY_GROUP_AND_MANY,
                FileMenuModel.applyToSelectionTextKey(2, 1), "组外 ≥2 + 组 → 到组及多项");
        assertEquals(FileMenuModel.KEY_APPLY_GROUP_AND_MANY,
                FileMenuModel.applyToSelectionTextKey(5, 2), "组外 ≥2 + 多组 → 到组及多项");
        // 空集：保持现状（不新增档）—— 落在默认档，启用规则不变（仍只看 copied）
        assertEquals(FileMenuModel.KEY_APPLY_THIS,
                FileMenuModel.applyToSelectionTextKey(0, 0), "空集 → 默认档（不新增第 6 档）");

        // stateFor 必须把分类结果带出来（不能只在辅助函数里对）
        FileMenuModel.State s = FileMenuModel.stateFor(
                FileMenuModel.TargetType.BLANK,
                new ParamsInfo(false, false, true, true, 2, 1));
        assertEquals(FileMenuModel.KEY_APPLY_GROUP_AND_MANY, s.applyToSelectionTextKey());
        assertTrue(s.applyToSelection(), "启用规则不变：copied=true ⇒ 可用");
    }

    /**
     * 完整链路（功能二）：勾选集合优先于右键目标 → 实际作用集合 → 计数 → 5 档文案。
     * 用真实 {@link com.mhtextractor.app.ParamTargets#resolve}（B4 起该纯函数下沉 app 层）与
     * {@link ParamDomainController#countTargets}，覆盖"勾选优先"这一已核实基线。
     */
    @Test
    void checkedSelectionWinsOverContextTargetAndDrivesFiveTierText() {
        PreviewItem top1 = item("t1", null);
        PreviewItem top2 = item("t2", null);
        GroupItem<PreviewItem> group = new GroupItem<>(1, "g");
        PreviewItem member = new PreviewItem("C:/q10/m.mht", "m.mht", LocalDateTime.now(), "C:/q10");
        member.parentGroup = group;
        group.getMembers().add(member);

        // 勾选集合为空 → 回退右键目标（1 个组外项）
        List<Object> byFallback = com.mhtextractor.app.ParamTargets.resolve(
                List.of(top1, top2), top1);
        assertEquals(List.of(top1), byFallback, "无勾选 → 回退右键目标");
        assertEquals(FileMenuModel.KEY_APPLY_THIS,
                FileMenuModel.applyToSelectionTextKey(
                        countProjectedTargets(byFallback).topLevelCount(),
                        countProjectedTargets(byFallback).distinctGroupCount()));

        // 有勾选 → 勾选优先，右键目标被忽略
        top1.selected = true;
        top2.selected = true;
        List<Object> byChecked = com.mhtextractor.app.ParamTargets.resolve(List.of(top1, top2), member);
        assertEquals(List.of(top1, top2), byChecked, "抓勾选优先于右键目标");
        var c = countProjectedTargets(byChecked);
        assertEquals(2, c.topLevelCount());
        assertEquals(0, c.distinctGroupCount());
        assertEquals(FileMenuModel.KEY_APPLY_MANY,
                FileMenuModel.applyToSelectionTextKey(c.topLevelCount(), c.distinctGroupCount()));

        // 组内成员 + 组条目去重到同一组；再叠加 1 个组外项 → "到组及此项"
        top2.selected = false;
        member.selected = true;
        List<Object> mixedSet = com.mhtextractor.app.ParamTargets.resolve(List.of(top1, member), group);
        var mc = countProjectedTargets(mixedSet);
        assertEquals(1, mc.topLevelCount(), "组外项数");
        assertEquals(1, mc.distinctGroupCount(), "去重组数");
        assertEquals(FileMenuModel.KEY_APPLY_GROUP_AND_THIS,
                FileMenuModel.applyToSelectionTextKey(mc.topLevelCount(), mc.distinctGroupCount()));
    }

    /** The backend resolver still owns targets; the GUI counter now receives their display rows. */
    private static ParamDomainController.TargetCounts countProjectedTargets(List<Object> targets) {
        var groups = new java.util.IdentityHashMap<GroupItem<?>, com.mhtextractor.uicore.GroupRow<com.mhtextractor.uicore.PreviewRow>>();
        java.util.function.Function<GroupItem<?>, com.mhtextractor.uicore.GroupRow<com.mhtextractor.uicore.PreviewRow>> groupRow = group ->
                groups.computeIfAbsent(group, g -> new com.mhtextractor.uicore.GroupRow<>(new com.mhtextractor.uiport.GroupVm(
                        g.getGroupNo(), g.getName(), g.isExpanded(), g.isDefaultNamed(), List.of())));
        List<Object> rows = targets.stream().map(target -> {
            if (target instanceof GroupItem<?> group) return (Object) groupRow.apply(group);
            PreviewItem item = (PreviewItem) target;
            var row = new com.mhtextractor.uicore.PreviewRow(new com.mhtextractor.uiport.ItemVm(
                    item.path, item.name, item.selected, item.removed, 0, com.mhtextractor.uiport.UpdateKind.NONE), ignored -> { });
            if (item.parentGroup != null) row.parentGroup = groupRow.apply(item.parentGroup);
            return (Object) row;
        }).toList();
        return ParamDomainController.countTargets(rows);
    }

    // ==================== 3d. R7.6：判定走 EffectiveParamsService ====================

    /**
     * R7.2/R7.6 的"共用证据"：用<b>真实</b> {@link EffectiveParamsService} 判"有生效参数 / 是否统一"，
     * 再把结果喂给真实的 {@code stateFor} —— 覆盖"两个目标生效参数相同（应可用）"与"不同（应禁用）"。
     * 若有人把判定改成"自己比签名/自己判 null"，本用例的第一、二条至少有一条会变红。
     */
    @Test
    void copyEnablementFollowsEffectiveParamsServiceForUniformAndMixedSelections() {
        EffectiveParamsService service = new EffectiveParamsService();
        EffectiveParamsService.Context ctx =
                new EffectiveParamsService.Context(false, null, null);   // 文本模式，两组全局参数都未确认

        PreviewItem a = item("a", paramsWithMarker("SAME"));
        PreviewItem b = item("b", paramsWithMarker("SAME"));
        PreviewItem c = item("c", paramsWithMarker("OTHER"));
        PreviewItem bare = item("bare", null);

        // 生效参数相同（且是不同实例）→ 可用
        assertTrue(service.isUniform(List.of(a, b), ctx), "装置自检：两个相同签名的配置应判为统一");
        assertNotSame(a.textItemParams, b.textItemParams,
                "装置自检：两份参数必须是不同实例（AppConfig 的 equals 是值语义，此处只认身份）");
        FileMenuModel.State same = stateFor(service, List.of(a, b), ctx);
        assertTrue(same.copyParams(), "两个目标生效参数相同 ⇒ 复制可用");
        assertEquals(FileMenuModel.KEY_COPY_PARAMS, same.copyParamsTextKey());

        // 生效参数不同 → 禁用，理由 = 参数不统一
        assertTrue(service.hasMixedEffectiveParams(List.of(a, c), ctx), "装置自检：SAME/OTHER 应判为混合");
        FileMenuModel.State mixed = stateFor(service, List.of(a, c), ctx);
        assertFalse(mixed.copyParams(), "参数不同 ⇒ 复制禁用");
        assertEquals(FileMenuModel.KEY_COPY_MIXED_PARAMS, mixed.copyParamsTextKey());

        // 一半有参数一半没有 → 服务口径把 null 当成一种取值 ⇒ 同样"不统一"（R7.2 的保守解读）
        FileMenuModel.State half = stateFor(service, List.of(a, bare), ctx);
        assertFalse(half.copyParams(), "一半有参数 ⇒ 禁用");
        assertEquals(FileMenuModel.KEY_COPY_MIXED_PARAMS, half.copyParamsTextKey());

        // 全都没有生效参数 → 禁用，理由 = 无参数
        FileMenuModel.State none = stateFor(service, List.of(bare), ctx);
        assertFalse(none.copyParams(), "无生效参数 ⇒ 禁用");
        assertEquals(FileMenuModel.KEY_COPY_NO_PARAMS, none.copyParamsTextKey());

        // 全局参数一确认，无独立参数的目标也有了生效参数 → 理由从"无参数"变为可用
        EffectiveParamsService.Context withGlobal =
                new EffectiveParamsService.Context(false, paramsWithMarker("GLOBAL"), null);
        FileMenuModel.State globalFallback = stateFor(service, List.of(bare), withGlobal);
        assertTrue(globalFallback.copyParams(), "全局参数已确认 ⇒ 目标有生效参数（回落全局）⇒ 可用");
    }

    /** 把真实服务的判定结果送进真实的 {@code stateFor}（4 个布尔一次给全的形态） */
    private static FileMenuModel.State stateFor(EffectiveParamsService service, List<Object> targets,
                                                      EffectiveParamsService.Context ctx) {
        return FileMenuModel.stateFor(FileMenuModel.TargetType.TOP_ITEM,
                new ParamsInfo(false,
                        service.anyHasEffectiveParams(targets, ctx), service.isUniform(targets, ctx), false, 0, 0));
    }

    private static PreviewItem item(String name, AppConfig textParams) {
        PreviewItem it = new PreviewItem("C:/q10/" + name + ".mht", name + ".mht",
                LocalDateTime.now(), "C:/q10");
        it.textItemParams = textParams;
        return it;
    }

    private static AppConfig paramsWithMarker(String marker) {
        AppConfig c = new AppConfig();
        c.setStartMarker(marker);
        return c;
    }

    // ==================== 4. 规则落到菜单项上（apply 的字段映射） ====================

    /** 桩宿主：目标类型与参数判定快照由测试预置；14 个动作只记名字。 */
    private static final class Stub implements FileListContextMenu.Actions {
        FileMenuModel.TargetType kind = FileMenuModel.TargetType.BLANK;
        ParamsInfo info = info(false);
        final List<String> calls = new ArrayList<>();

        @Override public boolean isDragActive() { return false; }
        @Override public long visibleCount() { return 1; }
        @Override public void selectRow(Object target) { }
        @Override public FileMenuModel.TargetType targetType(Object target) { return kind; }
        @Override public boolean isLiveTarget(Object item) { return true; }
        @Override public ParamsInfo paramsInfo(Object target) { return info; }
        @Override public void select(Object target) { calls.add("select"); }
        @Override public void selectAll(Object target) { calls.add("selectAll"); }
        @Override public void openFolder(Object target) { calls.add("openFolder"); }
        @Override public void openInBrowser(Object target) { calls.add("openInBrowser"); }
        @Override public void viewSource(Object target) { calls.add("viewSource"); }
        @Override public void params(Object target) { calls.add("params"); }
        @Override public void clearParams(Object target) { calls.add("clearParams"); }
        @Override public void applyGlobal(Object target) { calls.add("applyGlobal"); }
        @Override public void copyParams(Object target) { calls.add("copyParams"); }
        @Override public void applyToSelection(Object target) { calls.add("applyToSelection"); }
        @Override public void group() { calls.add("group"); }
        @Override public void ungroup() { calls.add("ungroup"); }
        @Override public void remove() { calls.add("remove"); }
        @Override public void removeGroup(Object target) { calls.add("removeGroup"); }
    }

    /** 14 个菜单项都有实例；4 个分隔符没有实例 */
    @Test
    void everyMenuEntryHasItsOwnItemAndSeparatorsHaveNone() {
        FileListContextMenu menu = new FileListContextMenu(new Stub());
        int itemCount = 0;
        for (FileMenuModel.Entry entry : FileMenuModel.LAYOUT) {
            if (entry.isSeparator()) {
                assertNull(menu.item(entry), "分隔符不应有菜单项实例：" + entry);
            } else {
                assertNotNull(menu.item(entry), "菜单项实例缺失：" + entry);
                itemCount++;
            }
        }
        assertEquals(14, itemCount, "菜单项恒 14 个");
        assertNotNull(menu.clearParamsItem(), "ctxClearParams 对应的项必须可取到（test-support 字段指向它）");
    }

    /**
     * 每个启用位的<b>落点</b>：{@code stateFor} 算对了但 {@code apply} 写错了项，同样会被这里抓住
     * （每项断言自己的 {@code isDisable()}）。同时验证"参数文案"在空白/非空白之间切换、
     * 以及 R7.2 的复制文案三态与 R7.3 的应用位。
     */
    @Test
    void applyLandsEveryFlagOnItsOwnMenuItem() {
        Stub stub = new Stub();
        FileListContextMenu menu = new FileListContextMenu(stub);

        for (FileMenuModel.TargetType kind : List.of(FileMenuModel.TargetType.BLANK,
                FileMenuModel.TargetType.TOP_ITEM, FileMenuModel.TargetType.MEMBER,
                FileMenuModel.TargetType.GROUP)) {
            stub.kind = kind;
            for (boolean independent : new boolean[] {false, true}) {
                stub.info = info(independent);
                menu.updateState("target");
                FileMenuModel.State s = FileMenuModel.stateFor(kind, info(independent));
                String what = kind + "（independent=" + independent + "）";

                assertEquals(!s.select(), disabled(menu, FileMenuModel.Entry.SELECT), what + "：勾选");
                assertEquals(!s.selectAll(), disabled(menu, FileMenuModel.Entry.SELECT_ALL), what + "：全选");
                assertEquals(!s.openFolder(), disabled(menu, FileMenuModel.Entry.OPEN_FOLDER), what + "：打开目录");
                assertEquals(!s.openInBrowser(), disabled(menu, FileMenuModel.Entry.OPEN_IN_BROWSER), what + "：浏览器查看");
                assertEquals(!s.viewSource(), disabled(menu, FileMenuModel.Entry.VIEW_SOURCE), what + "：查看源码");
                assertEquals(!s.params(), disabled(menu, FileMenuModel.Entry.PARAMS), what + "：设置参数");
                assertEquals(!s.clearParams(), disabled(menu, FileMenuModel.Entry.CLEAR_PARAMS), what + "：清除参数");
                assertEquals(!s.applyGlobal(), disabled(menu, FileMenuModel.Entry.APPLY_GLOBAL), what + "：应用到全局");
                assertEquals(!s.copyParams(), disabled(menu, FileMenuModel.Entry.COPY_PARAMS), what + "：复制参数");
                assertEquals(!s.applyToSelection(), disabled(menu, FileMenuModel.Entry.APPLY_TO_SELECTION),
                        what + "：应用到所选");
                assertEquals(!s.group(), disabled(menu, FileMenuModel.Entry.GROUP), what + "：建组");
                assertEquals(!s.ungroup(), disabled(menu, FileMenuModel.Entry.UNGROUP), what + "：拆分组");
                assertEquals(!s.remove(), disabled(menu, FileMenuModel.Entry.REMOVE), what + "：移除");
                assertEquals(!s.removeGroup(), disabled(menu, FileMenuModel.Entry.REMOVE_GROUP), what + "：移除组");

                String expectedKey = s.paramsGlobalText()
                        ? FileMenuModel.KEY_PARAMS_GLOBAL : FileMenuModel.KEY_PARAMS;
                assertEquals(I18n.get(expectedKey), menu.item(FileMenuModel.Entry.PARAMS).getText(),
                        what + "：参数文案键");
                assertEquals(I18n.get(s.copyParamsTextKey()),
                        menu.item(FileMenuModel.Entry.COPY_PARAMS).getText(), what + "：复制参数文案键");
                assertEquals(I18n.get(s.applyToSelectionTextKey()),
                        menu.item(FileMenuModel.Entry.APPLY_TO_SELECTION).getText(),
                        what + "：应用到所选文案键（功能二：必须落到菜单项上）");
            }
        }

        // R7.2 落点：三种参数判定 → 菜单项文本随之切换（真实 apply(State) 写到真实 MenuItem 上）
        for (String key : List.of(FileMenuModel.KEY_COPY_NO_PARAMS,
                FileMenuModel.KEY_COPY_MIXED_PARAMS, FileMenuModel.KEY_COPY_PARAMS)) {
            stub.info = switch (key) {
                case FileMenuModel.KEY_COPY_NO_PARAMS -> new ParamsInfo(false, false, true, false, 0, 0);
                case FileMenuModel.KEY_COPY_MIXED_PARAMS -> new ParamsInfo(false, true, false, false, 0, 0);
                default -> new ParamsInfo(false, true, true, false, 0, 0);
            };
            stub.kind = FileMenuModel.TargetType.TOP_ITEM;
            menu.updateState("target");
            assertEquals(I18n.get(key), menu.item(FileMenuModel.Entry.COPY_PARAMS).getText(),
                    "复制参数文案必须落到菜单项上：" + key);
            assertEquals(!FileMenuModel.KEY_COPY_PARAMS.equals(key),
                    disabled(menu, FileMenuModel.Entry.COPY_PARAMS), "禁用位与文案键必须一致：" + key);
        }
    }

    private static boolean disabled(FileListContextMenu menu, FileMenuModel.Entry entry) {
        return menu.item(entry).isDisable();
    }

    // ==================== 5. 14 个动作的转发接线 ====================

    /**
     * 逐项触发，断言触发的是<b>它自己</b>那个动作 —— 抓"复制粘贴错接线"（例如把"应用到全局"接到
     * "清除参数"、把"复制参数"接到"应用到所选"）。{@code target} 未设置时为 {@code null}，
     * 与"空白区域右键"一致。
     */
    @Test
    void firingEachItemInvokesExactlyItsOwnAction() {
        Stub stub = new Stub();
        FileListContextMenu menu = new FileListContextMenu(stub);

        List<FileMenuModel.Entry> itemEntries = FileMenuModel.LAYOUT.stream()
                .filter(e -> !e.isSeparator()).toList();
        for (FileMenuModel.Entry entry : itemEntries) {
            stub.calls.clear();
            menu.item(entry).fire();
            assertEquals(1, stub.calls.size(), "触发 " + entry + " 必须恰好调用一次动作，实际 " + stub.calls);
        }
        // 14 个动作名必须一一对应（顺序与 LAYOUT 的菜单项顺序一致）
        List<String> fired = new ArrayList<>();
        for (FileMenuModel.Entry entry : itemEntries) {
            stub.calls.clear();
            menu.item(entry).fire();
            fired.add(stub.calls.get(0));
        }
        assertEquals(List.of("select", "selectAll", "openInBrowser", "openFolder", "viewSource",
                        "params", "clearParams", "applyGlobal", "copyParams", "applyToSelection",
                        "group", "ungroup", "remove", "removeGroup"),
                fired, "菜单项 → 动作的接线必须逐项对应（注意 openInBrowser 在 openFolder 之前）");
    }

    // ==================== 6. 右键位置拾取 ====================

    /**
     * 空白区域/无单元格 → {@code null}。另外两个分支（滚动条、已移除行）需要真实控件与真实单元格，
     * 由 GUI 探针 {@code tools/probes/ContextMenuProbe.java} 在带 toolkit 的 JVM 里覆盖（那里能建
     * {@code ScrollBar}，也能经反射拿到宿主的 {@code Actions} 实例直接问 {@code isLiveTarget}）。
     */
    @Test
    void pickFromReturnsNullWhenNoListCellIsHit() {
        FileListContextMenu menu = new FileListContextMenu(new Stub());
        assertNull(menu.pickFrom(null), "没有命中节点 → 空白区域");
        assertNull(menu.pickFrom(new Pane()), "命中普通节点且没有祖先单元格 → 空白区域");
    }
}
