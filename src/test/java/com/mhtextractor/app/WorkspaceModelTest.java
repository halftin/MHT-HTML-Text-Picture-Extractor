package com.mhtextractor.app;

import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;
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
 * 无头工作区模型测试（会话 18 · E4-4）—— 验收 V2「桩 UI 跑通**工作区**功能」的证据。
 *
 * <p>本类扮演"桩 UI"：只用 {@link WorkspaceModel} 的公开 API 完成
 * 添加 / 建组 / 拆组 / 移除 / 撤销，并断言工作区状态。**零 JavaFX**。</p>
 */
class WorkspaceModelTest {

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
    }

    // ==================== 导入 / 添加 ====================

    @Test
    void addItemsAppendsAndPushesOneUndoRecordPerBatch() {
        WorkspaceModel m = new WorkspaceModel();
        assertEquals(2, m.addItems(List.of(item("a.mht"), item("b.mht"))));
        assertEquals(2, m.allItems().size());
        assertEquals(2, m.visibleCount());
        // 一次导入 = 一条撤销记录（与 GUI 的"整批撤销"语义一致）
        assertEquals(1, m.undoDepth());
    }

    @Test
    void addItemsSkipsAlreadyPresentAndReturnsAddedCount() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht");
        assertEquals(1, m.addItems(List.of(a)));
        assertEquals(0, m.addItems(List.of(a)), "同一对象重复添加应被跳过");
        assertEquals(1, m.allItems().size());
    }

    // ==================== 建组 / 拆组 ====================

    @Test
    void groupCreatesGroupAtEarliestPositionAndClearsItemLevelParams() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        m.addItems(List.of(a, b, c));

        GroupItem<PreviewItem> g = m.group(List.of(a, c));

        assertNotNull(g, "两项应可建组");
        assertEquals(2, g.getMembers().size());
        assertSame(g, a.parentGroup);
        assertSame(g, c.parentGroup);
        assertNull(b.parentGroup, "未参与建组的条目应保持顶层");
        // 插入槽位 = 目标中最靠前的位置（a 原在索引 0）→ 组应在索引 0
        assertSame(g, m.topLevel().get(0));
        // 入组清除条目级参数（GUI 语义：组内成员不持有独立参数）
        assertNull(a.textItemParams);
        assertNull(a.imageItemParams);
        // 顶层只剩 组 + b
        assertEquals(2, m.topLevel().size());
    }

    @Test
    void groupWithFewerThanTwoItemsIsNoOp() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht");
        m.addItems(List.of(a));
        int depthBefore = m.undoDepth();

        assertNull(m.group(List.of(a)));
        assertNull(m.group(List.of()));
        assertEquals(1, m.topLevel().size(), "输入不足 2 项不得改变结构");
        assertEquals(depthBefore, m.undoDepth(), "无变更就不应产生撤销记录");
    }

    @Test
    void groupNameIsUniquified() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht"), d = item("d.mht");
        m.addItems(List.of(a, b, c, d));

        GroupItem<PreviewItem> g1 = m.group(List.of(a, b));
        GroupItem<PreviewItem> g2 = m.group(List.of(c, d));

        assertNotNull(g1);
        assertNotNull(g2);
        // 组号单调递增 → 默认名不同；这里断言两者名字互不相同（唯一化生效）
        assertFalse(g1.getName().equals(g2.getName()), "组名应被唯一化");
    }

    @Test
    void ungroupReturnsMembersAtOriginalPositionAndCopiesGroupParamsToMembers() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);
        var groupCfg = new com.mhtextractor.config.AppConfig();
        g.setTextParamsCache(groupCfg);

        List<PreviewItem> members = m.ungroup(g);

        assertEquals(2, members.size());
        assertTrue(m.topLevel().contains(a));
        assertTrue(m.topLevel().contains(b));
        assertFalse(m.topLevel().contains(g), "组应已从顶层移除");
        assertNull(a.parentGroup);
        // 组参数下发给成员（拆分前后成员生效参数不变）
        assertSame(groupCfg, a.textItemParams);
        assertSame(groupCfg, b.textItemParams);
    }

    @Test
    void ungroupOfGroupNotInTopLevelIsNoOp() {
        WorkspaceModel m = new WorkspaceModel();
        GroupItem<PreviewItem> orphan = new GroupItem<>(99, "orphan");
        assertTrue(m.ungroup(orphan).isEmpty());
        assertTrue(m.ungroup(null).isEmpty());
    }

    // ==================== 移除 ====================

    @Test
    void removeItemsHidesThemAndKeepsThemInDataOrder() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));

        assertEquals(1, m.removeItems(List.of(a)));

        assertTrue(a.removed, "移除 = 标记 removed（隐藏式）");
        assertEquals(1, m.visibleCount());
        assertEquals(2, m.allItems().size(), "数据序仍保留被移除项（供撤销恢复）");
    }

    @Test
    void removeItemsDissolvesGroupThatBecomesEmpty() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);
        assertEquals(1, m.groups().size());

        m.removeItems(List.of(a, b));

        assertTrue(m.groups().isEmpty(), "成员全被移除后组应自动销毁");
        assertEquals(0, m.visibleCount());
    }

    // ==================== 撤销 ====================

    @Test
    void undoUndoesImport() {
        WorkspaceModel m = new WorkspaceModel();
        m.addItems(List.of(item("a.mht"), item("b.mht")));
        assertEquals(2, m.allItems().size());

        assertTrue(m.undo());

        assertEquals(0, m.allItems().size(), "撤销导入应移除本批新增");
    }

    @Test
    void undoUndoesRemove() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht");
        m.addItems(List.of(a));
        m.removeItems(List.of(a));
        assertTrue(a.removed);

        assertTrue(m.undo());

        assertFalse(a.removed, "撤销移除应恢复可见");
        assertEquals(1, m.visibleCount());
    }

    @Test
    void undoUndoesGroupAndRestoresFlatStructure() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);

        // 最后一条是 GROUP 记录
        assertTrue(m.undo());

        assertTrue(m.groups().isEmpty(), "撤销建组后应回到扁平结构");
        assertTrue(m.topLevel().contains(a));
        assertTrue(m.topLevel().contains(b));
    }

    @Test
    void undoOnEmptyStackReturnsFalse() {
        WorkspaceModel m = new WorkspaceModel();
        assertFalse(m.undo(), "栈空时撤销应返回 false 且无副作用");
    }

    /**
     * 尚未支持的撤销类型（参数类 / 拖拽类）：必须<b>不改变状态</b>且把记录原样压回，
     * 否则用户会看到"点了撤销但什么都没变"的静默不一致。
     */
    @Test
    void unsupportedUndoTypeIsPushedBackAndReportsFalse() {
        WorkspaceModel m = new WorkspaceModel();
        m.addItems(List.of(item("a.mht")));
        // 手动压入一条参数类记录（本模型尚未实现）
        m.pushUnsupportedForTest();

        int depthBefore = m.undoDepth();
        assertFalse(m.undo(), "未支持的类型应返回 false");
        assertEquals(depthBefore, m.undoDepth(), "记录应原样压回，栈深不变");
    }

    // ==================== 显示序 ====================

    @Test
    void displayOrderFlattensExpandedGroupsAndFiltersRemovedItems() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        m.addItems(List.of(a, b, c));
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);

        // 默认展开：组条目 + 其成员 + 顶层 c
        List<Object> order = m.displayOrder();
        assertTrue(order.contains(g));
        assertTrue(order.contains(a) && order.contains(b) && order.contains(c));

        // 折叠后成员不进入显示序
        g.setExpanded(false);
        List<Object> collapsed = m.displayOrder();
        assertFalse(collapsed.contains(a), "折叠组的成员不应出现在显示序");

        // 移除的条目被过滤
        g.setExpanded(true);
        m.removeItems(List.of(c));
        assertFalse(m.displayOrder().contains(c), "已移除项不应出现在显示序");
    }
}
