package com.mhtextractor.app;

import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;
import com.mhtextractor.util.UndoManager;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
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

    // ==================== 构造 / 共享存储 ====================

    /**
     * 共享构造：模型必须在调用方提供的列表上**直接**操作 —— 这是"模型与 GUI 共享数据序、
     * 迁移期间不会分叉"的全部依据。用「外部直写 → 模型可见」与「模型写 → 外部可见」
     * 双向断言锁住，防止日后有人改成防御性拷贝而悄悄恢复成两套状态。
     */
    @Test
    void sharedConstructorOperatesOnTheCallerProvidedList() {
        List<Object> shared = new ArrayList<>();
        WorkspaceModel m = new WorkspaceModel(shared);

        // ① 外部直写 → 模型可见（迁移期间尚未迁移的 GUI 直写必须被模型看到）
        PreviewItem a = item("a.mht");
        shared.add(a);
        assertEquals(1, m.allItems().size());
        assertEquals(1, m.visibleCount());

        // ② 模型写 → 外部可见（模型成为唯一写入口后 GUI 仍能渲染到）
        PreviewItem b = item("b.mht");
        m.addItems(List.of(b));
        assertTrue(shared.contains(b), "模型的新增必须落到调用方列表上（不得拷贝）");
        assertEquals(2, shared.size());

        // ③ 移除是同一份存储：隐藏式移除不改动列表长度
        m.removeItems(List.of(a));
        assertTrue(a.removed);
        assertEquals(2, shared.size());

        // ④ clear 同样作用于调用方的列表
        m.clear();
        assertTrue(shared.isEmpty());
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

    /** D7 整组移除：成员标记 removed、组条目从顶层摘除，但组对象**不销毁**（供撤销整体恢复） */
    @Test
    void removeGroupDropsGroupFromTopLevelButKeepsGroupObjectAlive() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        m.addItems(List.of(a, b, c));
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);

        assertEquals(2, m.removeGroup(g));

        assertTrue(a.removed && b.removed, "整组移除 = 全部成员标记 removed");
        assertFalse(m.topLevel().contains(g), "组条目应从顶层摘除");
        assertTrue(m.topLevel().contains(c), "未参与的顶层条目不受影响");
        assertEquals(1, m.visibleCount());
        assertEquals(2, g.getMembers().size(), "组对象保留（成员仍在 members 里，撤销时整体恢复）");
    }

    @Test
    void removeGroupOfGroupNotInTopLevelIsNoOp() {
        WorkspaceModel m = new WorkspaceModel();
        m.addItems(List.of(item("a.mht")));
        int depth = m.undoDepth();

        assertEquals(0, m.removeGroup(new GroupItem<>(99, "orphan")));
        assertEquals(0, m.removeGroup(null));

        assertEquals(depth, m.undoDepth(), "无变更就不应产生撤销记录");
        assertEquals(1, m.visibleCount());
    }

    /**
     * 撤销记录的**形状契约**：GUI 的 {@code REMOVE / REMOVE_GROUP} 两个分支共用同一段恢复代码，
     * 它靠 {@code destroyedGroups/destroyedIndexes} 把组插回原顶层位置。因此整组移除必须把
     * 「组 + 其原索引」放进这两个字段（而不是另造字段），否则撤销会把组插错位置。
     */
    @Test
    void removeGroupRecordCarriesGroupAndItsTopLevelIndexForUndo() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        m.addItems(List.of(a, b, c));
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);
        int idx = m.topLevel().indexOf(g);

        m.removeGroup(g);
        UndoManager.Record<PreviewItem> r = m.popRecord();

        assertNotNull(r);
        assertEquals(UndoManager.RecordType.REMOVE_GROUP, r.type());
        assertSame(g, r.group());
        assertEquals(idx, r.groupIndex());
        assertEquals(List.of(g), r.destroyedGroups());
        assertEquals(List.of(idx), r.destroyedIndexes());
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
