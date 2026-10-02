package com.mhtextractor.gui.controller;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import com.mhtextractor.i18n.I18n;

import javafx.scene.control.MenuItem;
import javafx.scene.layout.Pane;
import org.junit.jupiter.api.Test;

/**
 * {@link FileListContextMenu} 的纯逻辑测试（Q9a / P4b-①）。
 *
 * <p><b>为什么全部无头</b>：本类把"启用规则 / 顺序 / 文案键"做成了不依赖 JavaFX 构造的纯数据与纯函数
 * （构造器只建 {@code MenuItem}——实测无 toolkit 也能建），于是这些规则可以被单测直接钉死，
 * 不必启动 GUI。</p>
 *
 * <p><b>为什么不构造真实菜单</b>（实测，2026-10-03）：无头 JVM 里 {@code new SeparatorMenuItem()} /
 * {@code new ContextMenu()} 会以 {@code IllegalStateException: Toolkit not initialized} 失败；而
 * {@code WorkspaceFxAdapterTest} 末尾调了 {@code Platform.exit()}，之后的用例 {@code Platform.startup}
 * 必抛 {@code IllegalStateException: Platform.exit has been called} ⇒ "在单测里建真实菜单"会变成
 * <b>顺序依赖式假失败</b>。因此真实 {@code ContextMenu.getItems()} 的顺序由 GUI 探针
 * {@code tools/probes/ContextMenuProbe.java} 独立验证，这里只钉纯数据。</p>
 */
class FileListContextMenuTest {

    // ==================== 1. 恒 16 项同序（12 项 + 4 分隔符） ====================

    /**
     * 顺序与分隔符位置：期望值在这里<b>逐项写死</b>（不复用 {@code LAYOUT}），因此它是一份独立副本 ——
     * {@code LAYOUT} 被改动时本断言必失败。分隔符位置 = 搬前 {@code MainController:1200-1209} 的
     * 分组边界；⚠️ {@code OPEN_IN_BROWSER} 必须排在 {@code OPEN_FOLDER} 之前。
     */
    @Test
    void layoutIsExactlyTheOriginalSixteenEntriesInOrder() {
        List<FileListContextMenu.Entry> expected = List.of(
                FileListContextMenu.Entry.SELECT,
                FileListContextMenu.Entry.SELECT_ALL,
                FileListContextMenu.Entry.SEPARATOR_1,
                FileListContextMenu.Entry.OPEN_IN_BROWSER,
                FileListContextMenu.Entry.OPEN_FOLDER,
                FileListContextMenu.Entry.VIEW_SOURCE,
                FileListContextMenu.Entry.SEPARATOR_2,
                FileListContextMenu.Entry.PARAMS,
                FileListContextMenu.Entry.CLEAR_PARAMS,
                FileListContextMenu.Entry.APPLY_GLOBAL,
                FileListContextMenu.Entry.SEPARATOR_3,
                FileListContextMenu.Entry.GROUP,
                FileListContextMenu.Entry.UNGROUP,
                FileListContextMenu.Entry.SEPARATOR_4,
                FileListContextMenu.Entry.REMOVE,
                FileListContextMenu.Entry.REMOVE_GROUP);

        assertEquals(16, FileListContextMenu.LAYOUT.size(), "菜单恒 16 项（12 项 + 4 分隔符）");
        assertEquals(expected, FileListContextMenu.LAYOUT, "菜单项顺序必须与搬前逐项一致");

        int separators = 0;
        for (int i = 0; i < FileListContextMenu.LAYOUT.size(); i++) {
            FileListContextMenu.Entry entry = FileListContextMenu.LAYOUT.get(i);
            if (entry.isSeparator()) {
                separators++;
                assertTrue(entry.name().startsWith("SEPARATOR_"), "分隔符条目应命名为 SEPARATOR_*：" + entry);
            }
        }
        assertEquals(4, separators, "分隔符恒 4 个");
        // 分组边界（0-based 下标）—— 分隔符位置钉死，防"顺序对但分组挪了"
        assertEquals(List.of(2, 6, 10, 13),
                List.of(FileListContextMenu.Entry.SEPARATOR_1, FileListContextMenu.Entry.SEPARATOR_2,
                        FileListContextMenu.Entry.SEPARATOR_3, FileListContextMenu.Entry.SEPARATOR_4).stream()
                        .map(FileListContextMenu.LAYOUT::indexOf).toList(),
                "4 个分隔符的下标必须是 2 / 6 / 10 / 13");
    }

    // ==================== 2. 文案键逐字不变（本步不新增键） ====================

    @Test
    void textKeysMatchTheOriginalTwelveKeys() {
        assertEquals(12, FileListContextMenu.TEXT_KEYS.size(), "只有 12 个菜单项有文案键");
        assertEquals("list.menuSelect", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.SELECT));
        assertEquals("list.menuSelectAll", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.SELECT_ALL));
        assertEquals("list.menuOpenFolder", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.OPEN_FOLDER));
        assertEquals("browser.menuOpen", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.OPEN_IN_BROWSER));
        assertEquals("browser.menuViewSource", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.VIEW_SOURCE));
        assertEquals("params.menuSet", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.PARAMS));
        assertEquals("params.menuClear", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.CLEAR_PARAMS));
        assertEquals("params.menuApplyGlobal", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.APPLY_GLOBAL));
        assertEquals("group.menuMerge", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.GROUP));
        assertEquals("group.menuUngroup", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.UNGROUP));
        assertEquals("list.menuRemove", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.REMOVE));
        assertEquals("group.menuRemove", FileListContextMenu.TEXT_KEYS.get(FileListContextMenu.Entry.REMOVE_GROUP));

        // 动态文案（空白区域 → 全局参数入口）
        assertEquals("params.menuSet", FileListContextMenu.KEY_PARAMS);
        assertEquals("params.menuSetGlobal", FileListContextMenu.KEY_PARAMS_GLOBAL);

        // 分隔符不得有文案键
        for (FileListContextMenu.Entry entry : FileListContextMenu.LAYOUT) {
            if (entry.isSeparator()) {
                assertNull(FileListContextMenu.TEXT_KEYS.get(entry), "分隔符不应有文案键：" + entry);
            } else {
                assertNotNull(FileListContextMenu.TEXT_KEYS.get(entry), "菜单项必须有文案键：" + entry);
            }
        }
    }

    // ==================== 3. 启用规则（四种目标 × 独立参数两种取值） ====================

    /**
     * §1.4 的规则逐条对账。{@code itemScoped} = 「打开所在文件夹 / 在浏览器中查看 / 查看源码 /
     * 建组 / 移除」这五个共用"是项目条目"的条件。
     */
    private static void assertMatrix(FileListContextMenu.TargetType kind,
                                     boolean select,
                                     boolean itemScoped,
                                     boolean paramsGlobal,
                                     boolean group,
                                     boolean ungroup,
                                     boolean remove,
                                     boolean removeGroup) {
        for (boolean independent : new boolean[] {false, true}) {
            FileListContextMenu.State s = FileListContextMenu.stateFor(kind, independent);
            String what = kind + "（hasIndependentParams=" + independent + "）";
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
        }
    }

    @Test
    void enableRulesMatchTheOriginalMatrix() {
        // 空白区域：只有"全选 / 设置参数"可用，参数文案走全局入口
        assertMatrix(FileListContextMenu.TargetType.BLANK, false, false, true, false, false, false, false);
        // 顶层条目：勾选 + 条目类动作 + 建组 + 移除；拆分组与移除组禁用
        assertMatrix(FileListContextMenu.TargetType.TOP_ITEM, true, true, false, true, false, true, false);
        // 组内成员：与顶层条目同，但"移除组"可用
        assertMatrix(FileListContextMenu.TargetType.MEMBER, true, true, false, true, false, true, true);
        // 组条目：勾选 + 拆分组 + 移除组；条目类动作、建组、移除禁用（组条目走"移除组"）
        assertMatrix(FileListContextMenu.TargetType.GROUP, true, false, false, false, true, false, true);
    }

    /**
     * "非空但既不是条目也不是组"的目标：搬前 {@code updateContextMenuState} 的头四行会给出四个 false，
     * 与空白区域<b>不等价</b>（勾选项仍启用、参数文案仍是普通项）—— {@link FileListContextMenu.TargetType}
     * 保留四个布尔而不是枚举，正是为了不改这个行为。
     */
    @Test
    void unknownNonNullTargetIsNotTreatedAsBlank() {
        FileListContextMenu.TargetType unknown = new FileListContextMenu.TargetType(false, false, false, false);
        FileListContextMenu.State s = FileListContextMenu.stateFor(unknown, false);
        assertTrue(s.select(), "非空目标：勾选项仍然启用（与空白区域不同）");
        assertNotEquals(FileListContextMenu.stateFor(FileListContextMenu.TargetType.BLANK, false), s,
                "未知非空目标不得与空白区域等价");
        assertEquals(false, s.paramsGlobalText(), "非空目标：参数文案不是全局入口");
        assertEquals(false, s.group(), "非空目标：建组禁用");
        assertEquals(false, s.ungroup(), "非空目标：拆分组禁用");
        assertEquals(false, s.remove(), "非空目标：移除禁用");
        assertEquals(false, s.removeGroup(), "非空目标：移除组禁用");
    }

    // ==================== 4. 规则落到菜单项上（apply 的字段映射） ====================

    /** 桩宿主：目标类型与"是否独立参数"由测试预置；12 个动作只记名字。 */
    private static final class Stub implements FileListContextMenu.Actions {
        FileListContextMenu.TargetType kind = FileListContextMenu.TargetType.BLANK;
        boolean independent;
        final List<String> calls = new ArrayList<>();

        @Override public boolean isDragActive() { return false; }
        @Override public long visibleCount() { return 1; }
        @Override public void selectRow(Object target) { }
        @Override public FileListContextMenu.TargetType targetType(Object target) { return kind; }
        @Override public boolean isLiveTarget(Object item) { return true; }
        @Override public boolean hasIndependentParams(Object target) { return independent; }
        @Override public void select(Object target) { calls.add("select"); }
        @Override public void selectAll(Object target) { calls.add("selectAll"); }
        @Override public void openFolder(Object target) { calls.add("openFolder"); }
        @Override public void openInBrowser(Object target) { calls.add("openInBrowser"); }
        @Override public void viewSource(Object target) { calls.add("viewSource"); }
        @Override public void params(Object target) { calls.add("params"); }
        @Override public void clearParams(Object target) { calls.add("clearParams"); }
        @Override public void applyGlobal(Object target) { calls.add("applyGlobal"); }
        @Override public void group() { calls.add("group"); }
        @Override public void ungroup() { calls.add("ungroup"); }
        @Override public void remove() { calls.add("remove"); }
        @Override public void removeGroup(Object target) { calls.add("removeGroup"); }
    }

    /** 12 个菜单项都有实例；4 个分隔符没有实例 */
    @Test
    void everyMenuEntryHasItsOwnItemAndSeparatorsHaveNone() {
        FileListContextMenu menu = new FileListContextMenu(new Stub());
        int itemCount = 0;
        for (FileListContextMenu.Entry entry : FileListContextMenu.LAYOUT) {
            if (entry.isSeparator()) {
                assertNull(menu.item(entry), "分隔符不应有菜单项实例：" + entry);
            } else {
                assertNotNull(menu.item(entry), "菜单项实例缺失：" + entry);
                itemCount++;
            }
        }
        assertEquals(12, itemCount, "菜单项恒 12 个");
        assertNotNull(menu.clearParamsItem(), "ctxClearParams 对应的项必须可取到（test-support 字段指向它）");
    }

    /**
     * 每个启用位的<b>落点</b>：{@code stateFor} 算对了但 {@code apply} 写错了项，同样会被这里抓住
     * （每项断言自己的 {@code isDisable()}）。同时验证"参数文案"在空白/非空白之间切换。
     */
    @Test
    void applyLandsEveryFlagOnItsOwnMenuItem() {
        Stub stub = new Stub();
        FileListContextMenu menu = new FileListContextMenu(stub);

        for (FileListContextMenu.TargetType kind : List.of(FileListContextMenu.TargetType.BLANK,
                FileListContextMenu.TargetType.TOP_ITEM, FileListContextMenu.TargetType.MEMBER,
                FileListContextMenu.TargetType.GROUP)) {
            stub.kind = kind;
            for (boolean independent : new boolean[] {false, true}) {
                stub.independent = independent;
                menu.updateState("target");
                FileListContextMenu.State s = FileListContextMenu.stateFor(kind, independent);
                String what = kind + "（hasIndependentParams=" + independent + "）";

                assertEquals(!s.select(), disabled(menu, FileListContextMenu.Entry.SELECT), what + "：勾选");
                assertEquals(!s.selectAll(), disabled(menu, FileListContextMenu.Entry.SELECT_ALL), what + "：全选");
                assertEquals(!s.openFolder(), disabled(menu, FileListContextMenu.Entry.OPEN_FOLDER), what + "：打开目录");
                assertEquals(!s.openInBrowser(), disabled(menu, FileListContextMenu.Entry.OPEN_IN_BROWSER), what + "：浏览器查看");
                assertEquals(!s.viewSource(), disabled(menu, FileListContextMenu.Entry.VIEW_SOURCE), what + "：查看源码");
                assertEquals(!s.params(), disabled(menu, FileListContextMenu.Entry.PARAMS), what + "：设置参数");
                assertEquals(!s.clearParams(), disabled(menu, FileListContextMenu.Entry.CLEAR_PARAMS), what + "：清除参数");
                assertEquals(!s.applyGlobal(), disabled(menu, FileListContextMenu.Entry.APPLY_GLOBAL), what + "：应用到全局");
                assertEquals(!s.group(), disabled(menu, FileListContextMenu.Entry.GROUP), what + "：建组");
                assertEquals(!s.ungroup(), disabled(menu, FileListContextMenu.Entry.UNGROUP), what + "：拆分组");
                assertEquals(!s.remove(), disabled(menu, FileListContextMenu.Entry.REMOVE), what + "：移除");
                assertEquals(!s.removeGroup(), disabled(menu, FileListContextMenu.Entry.REMOVE_GROUP), what + "：移除组");

                String expectedKey = s.paramsGlobalText()
                        ? FileListContextMenu.KEY_PARAMS_GLOBAL : FileListContextMenu.KEY_PARAMS;
                assertEquals(I18n.get(expectedKey), menu.item(FileListContextMenu.Entry.PARAMS).getText(),
                        what + "：参数文案键");
            }
        }
    }

    private static boolean disabled(FileListContextMenu menu, FileListContextMenu.Entry entry) {
        return menu.item(entry).isDisable();
    }

    // ==================== 5. 12 个动作的转发接线 ====================

    /**
     * 逐项触发，断言触发的是<b>它自己</b>那个动作 —— 抓"复制粘贴错接线"（例如把"应用到全局"接到
     * "清除参数"）。{@code target} 未设置时为 {@code null}，与"空白区域右键"一致。
     */
    @Test
    void firingEachItemInvokesExactlyItsOwnAction() {
        Stub stub = new Stub();
        FileListContextMenu menu = new FileListContextMenu(stub);

        List<FileListContextMenu.Entry> itemEntries = FileListContextMenu.LAYOUT.stream()
                .filter(e -> !e.isSeparator()).toList();
        for (FileListContextMenu.Entry entry : itemEntries) {
            stub.calls.clear();
            menu.item(entry).fire();
            assertEquals(1, stub.calls.size(), "触发 " + entry + " 必须恰好调用一次动作，实际 " + stub.calls);
        }
        // 12 个动作名必须一一对应（顺序与 LAYOUT 的菜单项顺序一致）
        List<String> fired = new ArrayList<>();
        for (FileListContextMenu.Entry entry : itemEntries) {
            stub.calls.clear();
            menu.item(entry).fire();
            fired.add(stub.calls.get(0));
        }
        assertEquals(List.of("select", "selectAll", "openInBrowser", "openFolder", "viewSource",
                        "params", "clearParams", "applyGlobal", "group", "ungroup", "remove", "removeGroup"),
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
