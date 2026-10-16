package com.mhtextractor.app;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

    // ==================== 构造 / 自持存储 / 查询快照 ====================

    /**
     * 模型<b>自持</b>存储：两个独立构造的模型互不影响，且"外部拿到的初始列表"不再是模型的存储。
     *
     * <p><b>本测试取代了旧的 {@code sharedConstructorOperatesOnTheCallerProvidedList}</b>
     * （Q4/P3 指南第 1 条：删除"外部列表直写后模型可见"的共享构造测试）。旧测试锁的是
     * "模型与 GUI 共用同一个可变对象"，而那份共享正是本步要拆掉的东西 ——
     * 它让 GUI 的任何直写绕过撤销记录与结构维护而静默分叉。</p>
     */
    @Test
    void modelsOwnTheirOwnStorageAndDoNotShareTheCallerList() {
        List<Object> caller = new ArrayList<>();
        PreviewItem a = item("a.mht");
        caller.add(a);

        WorkspaceModel m1 = new WorkspaceModel();
        // 复制型初始导入：进的是模型自己的存储，调用方列表随后怎么改都与模型无关
        m1.importAll(caller);
        assertEquals(1, m1.allItems().size());

        caller.add(item("b.mht"));
        caller.clear();
        assertTrue(caller.isEmpty());
        assertEquals(1, m1.allItems().size(), "调用方列表的后续改动不得影响模型（已不共享存储）");
        assertSame(a, m1.allItems().get(0));

        // 两个独立模型互不影响
        WorkspaceModel m2 = new WorkspaceModel();
        m2.addItems(List.of(item("c.mht")));
        assertEquals(1, m1.allItems().size(), "第二个模型的新增不得出现在第一个模型里");
        assertEquals(1, m2.allItems().size());
        assertNotSame(m1.topLevel().get(0), m2.topLevel().get(0));
    }

    /**
     * 三个查询都只返回<b>不可变快照</b>：{@code add} / {@code clear} 抛
     * {@code UnsupportedOperationException}，且不影响模型内部状态（指南 §3.4-2）。
     */
    @Test
    void queryResultsAreImmutableSnapshots() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        assertNotNull(m.group(List.of(a)));

        List<List<?>> snapshots = List.of(
                m.topLevel(), m.allItems(), m.displayOrder(), m.groups(), m.applyUndoAffected());
        for (List<?> snapshot : snapshots) {
            assertThrows(UnsupportedOperationException.class, snapshot::clear,
                    "查询结果必须不可变（不可 clear）：" + snapshot);
            assertThrows(UnsupportedOperationException.class, () -> snapshot.add(null),
                    "查询结果必须不可变（不可 add）：" + snapshot);
        }

        // 不可变之外还得是"快照"：先前取到的那一份不随后续变更而变
        List<Object> top = m.topLevel();
        List<PreviewItem> all = m.allItems();
        List<Object> display = m.displayOrder();
        PreviewItem c = item("c.mht");
        m.addItems(List.of(c));
        assertFalse(top.contains(c), "topLevel() 应是快照");
        assertFalse(all.contains(c), "allItems() 应是快照");
        assertFalse(display.contains(c), "displayOrder() 应是快照");
        assertTrue(m.topLevel().contains(c), "模型自身仍应看到新条目");
    }

    /** {@code topLevelView()} 是"不可写"的<b>实时视图</b>：写抛异常，读永远是最新结构。 */
    @Test
    void topLevelViewIsLiveButUnwritable() {
        WorkspaceModel m = new WorkspaceModel();
        List<Object> view = m.topLevelView();

        assertTrue(view.isEmpty());
        PreviewItem a = item("a.mht");
        m.addItems(List.of(a));
        assertEquals(List.of(a), view, "视图必须实时反映数据序（这正是 GUI items 入口需要它的原因）");

        assertThrows(UnsupportedOperationException.class, () -> view.add(item("b.mht")),
                "topLevelView() 必须是只读视图");
        assertThrows(UnsupportedOperationException.class, view::clear);
        assertEquals(1, m.topLevel().size(), "只读视图上的写尝试不得影响模型");
    }

    // ==================== 变更发布 ====================

    /** 每条成功命令发布<b>恰好一次</b>变更，载荷是本次命令直接作用/直接产出的条目。 */
    @Test
    void eachCommandPublishesExactlyOnceWithCorrectPayload() {
        WorkspaceModel m = new WorkspaceModel();
        List<WorkspaceChange> seen = new ArrayList<>();
        m.addListener(seen::add);

        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        m.addItems(List.of(a, b));
        assertEquals(1, seen.size());
        assertEquals(WorkspaceChange.Kind.ITEMS_ADDED, seen.get(0).kind());
        assertEquals(List.of(a, b), seen.get(0).affected());

        seen.clear();
        m.appendItems(List.of(c));
        assertEquals(1, seen.size());
        assertEquals(WorkspaceChange.Kind.ITEMS_ADDED, seen.get(0).kind());
        assertEquals(List.of(c), seen.get(0).affected(), "结构原语也只发布一次");

        seen.clear();
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertEquals(1, seen.size(), "建组只发布一次");
        assertEquals(WorkspaceChange.Kind.GROUPED, seen.get(0).kind());
        assertEquals(List.of(a, b), seen.get(0).affected());

        seen.clear();
        m.removeItems(List.of(c));
        assertEquals(1, seen.size());
        assertEquals(WorkspaceChange.Kind.ITEMS_REMOVED, seen.get(0).kind());
        assertEquals(List.of(c), seen.get(0).affected());

        seen.clear();
        List<PreviewItem> members = m.ungroup(g);
        assertEquals(1, seen.size());
        assertEquals(WorkspaceChange.Kind.UNGROUPED, seen.get(0).kind());
        assertEquals(members, seen.get(0).affected());

        seen.clear();
        assertTrue(m.undo());
        assertEquals(1, seen.size(), "撤销一步只发布一次");
        assertEquals(WorkspaceChange.Kind.UNDO_APPLIED, seen.get(0).kind());

        seen.clear();
        GroupItem<PreviewItem> g2 = m.group(List.of(a, b));
        assertNotNull(g2);
        seen.clear();
        m.removeGroup(g2);
        assertEquals(1, seen.size());
        assertEquals(WorkspaceChange.Kind.GROUP_REMOVED, seen.get(0).kind());

        seen.clear();
        m.clear();
        assertEquals(1, seen.size());
        assertEquals(WorkspaceChange.Kind.CLEARED, seen.get(0).kind());
    }

    /**
     * 失败 / no-op 命令<b>不</b>发布变更 —— "收到变更"因此蕴含"状态真的变了"，
     * 订阅方不需要自己去比对状态。
     */
    @Test
    void failedOrNoOpCommandsPublishNothing() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht");
        m.addItems(List.of(a));
        List<WorkspaceChange> seen = new ArrayList<>();
        m.addListener(seen::add);

        assertEquals(0, m.addItems(List.of(a)), "同一对象重复添加被身份跳过");
        assertNull(m.group(List.of()), "空目标建组是 no-op");
        assertTrue(m.ungroup(new GroupItem<>(99, "不在顶层")).isEmpty(), "组不在顶层 → no-op");
        assertEquals(0, m.removeItems(List.of()), "空移除是 no-op");
        assertEquals(0, m.removeGroup(new GroupItem<>(98, "不在顶层")));
        assertFalse(m.applyResolvedDrop(null).changed(), "空操作拖放是 no-op");
        m.push(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of()));
        assertFalse(m.undo(), "不支持的撤销类型：弹出后原样压回");

        assertTrue(seen.isEmpty(), "失败 / no-op 命令不得发布变更，实际发布了：" + seen);

        // 原位拖放（changed == false）同样不发布：结构没变就不该有变更事件
        WorkspaceModel.DropResult drop = m.applyResolvedDrop(new DropResolver.InsertTop(a, 1));
        assertFalse(drop.changed(), "前置条件：原位拖放不得报告结构变化");
        assertTrue(seen.isEmpty(), "原位拖放不得发布变更");
    }

    /** 退订后不再收到变更；重复订阅同一个回调只算一次。 */
    @Test
    void listenerCanBeRemovedAndIsDeduplicated() {
        WorkspaceModel m = new WorkspaceModel();
        List<WorkspaceChange> seen = new ArrayList<>();
        java.util.function.Consumer<WorkspaceChange> listener = seen::add;
        m.addListener(listener);
        m.addListener(listener);
        m.addItems(List.of(item("a.mht")));
        assertEquals(1, seen.size(), "同一订阅者只回调一次");

        m.removeListener(listener);
        m.addItems(List.of(item("b.mht")));
        assertEquals(1, seen.size(), "退订后不得再收到变更");
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

    /**
     * 结构原语 {@code appendItems} / {@code appendGroup}：只写数据序，**不去重、不压撤销记录**。
     *
     * <p>与语义操作 {@link WorkspaceModel#addItems} 的区别是导入路径必需的 ——
     * 导入的去重已由 {@code IncrementalImport} 完成，而撤销记录必须保持
     * "一次用户导入 = 一条"的粒度（跨批次先累积、最后压一条）。</p>
     */
    @Test
    void appendPrimitivesWriteDataOrderWithoutPushOrDedupe() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");

        m.appendItems(List.of(a, b));
        assertEquals(2, m.topLevel().size());
        assertEquals(0, m.undoDepth(), "结构原语不得压撤销记录");

        m.appendItems(List.of(a));
        assertEquals(3, m.topLevel().size(), "appendItems 不做身份去重（去重由导入路径负责）");
        m.appendItems(null);
        assertEquals(3, m.topLevel().size(), "null 是 no-op");

        GroupItem<PreviewItem> g = new GroupItem<>(1, "组1");
        g.getMembers().add(a);
        m.appendGroup(g);
        assertSame(g, m.topLevel().get(3), "组应追加到末尾");
        m.appendGroup(g);
        assertEquals(4, m.topLevel().size(), "同一组对象不得被追加两次");
        m.appendGroup(null);
        assertEquals(4, m.topLevel().size());

        assertEquals(0, m.undoDepth(), "结构原语全程不得产生撤销记录");
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

    /**
     * 拒绝路径只剩"空输入"与"不在工作区内的条目"。
     *
     * <p><b>规格修正（接入生产时）</b>：原断言 {@code groupWithFewerThanTwoItemsIsNoOp} 把
     * "单项建组"也判为 no-op，但生产（{@code MainController.onGroupClicked}）允许单项建组
     * （R9：勾选集合为空时退化为当前选中项）。模型的 {@code size() < 2} 守卫与生产不符，
     * 已改为只拒绝空输入；单项建组见 {@link #singleItemCanBeGrouped()}。</p>
     */
    @Test
    void groupRejectsOnlyEmptyOrOutOfWorkspaceInput() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht");
        PreviewItem ghost = item("ghost.mht");   // 从未加入本工作区
        m.addItems(List.of(a));
        int depthBefore = m.undoDepth();

        assertNull(m.group(List.of()), "空列表不得建组");
        assertNull(m.group(null), "null 不得建组");
        assertNull(m.group(List.of(ghost)), "不在工作区内的条目不得建组（否则会在空工作区上建出组）");

        assertEquals(1, m.topLevel().size(), "拒绝路径不得改变结构");
        assertEquals(depthBefore, m.undoDepth(), "无变更就不应产生撤销记录");
    }

    /** 单项建组是合法操作（R9），与生产一致。 */
    @Test
    void singleItemCanBeGrouped() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht");
        m.addItems(List.of(a));

        GroupItem<PreviewItem> g = m.group(List.of(a));

        assertNotNull(g, "单项应可建组（R9）");
        assertEquals(List.of(a), g.getMembers());
        assertSame(g, a.parentGroup);
        assertEquals(1, m.topLevel().size());
        assertSame(g, m.topLevel().get(0));
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
        var groupCfg = new com.mhtextractor.model.AppConfig();
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
        m.push(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of()));

        int depthBefore = m.undoDepth();
        assertFalse(m.undo(), "未支持的类型应返回 false");
        assertEquals(depthBefore, m.undoDepth(), "记录应原样压回，栈深不变");
    }

    // ==================== applyUndo（GUI 复用的入口） ====================

    /**
     * {@code applyUndo} 是"结构恢复"的**唯一实现** —— {@link WorkspaceModel#undo()} 与生产 GUI
     * 的撤销链都经它。因此它必须支持整组移除（REMOVE_GROUP），生产会压入该类型。
     * 该类型此前只由 GUI 自己恢复（模型 {@code undo()} 会原样压回并返回 false）。
     */
    @Test
    void applyUndoHandlesRemoveGroupRecord() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);
        int idx = m.topLevel().indexOf(g);
        m.removeGroup(g);
        assertTrue(a.removed && b.removed);

        assertTrue(m.applyUndo(m.popRecord()), "整组移除的撤销必须由模型应用");

        assertFalse(a.removed, "成员应恢复可见");
        assertFalse(b.removed);
        assertSame(g, m.topLevel().get(idx), "组应按原顶层索引插回");
        assertEquals(List.of(a, b), g.getMembers(), "组成员不得因撤销而丢失");
        assertEquals(2, m.visibleCount());
    }

    /** {@code undo()} 现在也支持整组移除（此前会原样压回并返回 false）。 */
    @Test
    void undoNowSupportsRemoveGroup() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        GroupItem<PreviewItem> g = m.group(List.of(a, b));
        assertNotNull(g);
        m.removeGroup(g);

        assertTrue(m.undo(), "整组移除应可被 undo() 撤销");
        assertFalse(a.removed);
        assertTrue(m.topLevel().contains(g));
    }

    /**
     * 未支持的类型（参数类）必须返回 false 且**不改变任何状态**；
     * 且 {@code applyUndo} **不触碰撤销栈**（它只应用一条已弹出的记录）。
     *
     * <p><b>口径修订（Q0/P0，2026-10-02）</b>：本测试原以 {@code REORDER} 作为"未支持类型"的代表，
     * 但拖拽类（{@code REORDER / MERGE_GROUPS}）自 Q0/P0 起已纳入模型（见
     * {@link WorkspaceModel#applyUndo}），故改用仍未被模型支持的<b>参数类</b>
     * （{@code PARAM_SET / CLEAR_PARAMS}）作为代表 —— 断言意图（未支持 = false + 零副作用 + 不动栈）不变。</p>
     */
    @Test
    void applyUndoRejectsUnsupportedTypesWithoutTouchingStackOrStructure() {
        WorkspaceModel m = new WorkspaceModel();
        m.addItems(List.of(item("a.mht")));
        List<Object> before = m.topLevel();
        int depth = m.undoDepth();

        assertFalse(m.applyUndo(null), "null 记录应返回 false");
        assertFalse(m.applyUndo(new UndoManager.Record<>(UndoManager.RecordType.PARAM_SET, List.of())),
                "参数类（条目/组/全局）由 GUI 应用，模型应返回 false");
        assertFalse(m.applyUndo(new UndoManager.Record<>(UndoManager.RecordType.CLEAR_PARAMS, List.of())),
                "参数类（清除参数）由 GUI 应用，模型应返回 false");
        assertFalse(m.applyUndo(new UndoManager.Record<>(UndoManager.RecordType.APPLY_GLOBAL, List.of())),
                "参数类（应用到全局）由 GUI 应用，模型应返回 false");

        assertEquals(before, m.topLevel(), "未支持类型不得改变结构");
        assertEquals(depth, m.undoDepth(), "applyUndo 不得改动撤销栈");
        assertTrue(m.applyUndoAffected().isEmpty(), "未支持类型不得报告受影响条目");
    }

    // ==================== 拖拽落点（REORDER / MERGE_GROUPS，Q0/P0） ====================

    /**
     * REORDER 正向（顶层重排）：{@code applyResolvedDrop(InsertTop)} 后数据序正确、
     * {@code changed == true}、撤销栈深度 +1、{@code affected} 为被拖条目；
     * 再 {@code applyUndo} 后数据序**逐元素回到原样**。
     */
    @Test
    void applyResolvedDropReordersTopLevelAndUndoRestoresElementByElement() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        m.addItems(List.of(a, b, c));
        List<Object> beforeOrder = m.topLevel();
        int depthBefore = m.undoDepth();

        // 把 a 移到索引 2 之后（InsertTop 的 index 是"插入前的顶层索引"，apply 自行修正移除偏移）
        WorkspaceModel.DropResult drop = m.applyResolvedDrop(new DropResolver.InsertTop(a, 3));

        assertTrue(drop.changed(), "顶层重排应报告结构已变化");
        assertEquals(UndoManager.RecordType.REORDER, drop.type());
        assertEquals(List.of(b, c, a), m.topLevel(), "数据序应为 b, c, a");
        assertEquals(List.of(a), drop.affected(), "受影响条目应为被拖条目自身");
        assertNull(a.parentGroup, "顶层条目不得持有父组指针");
        assertEquals(depthBefore + 1, m.undoDepth(), "一次拖放只压一条撤销记录");

        UndoManager.Record<PreviewItem> record = m.popRecord();
        assertNotNull(record);
        assertTrue(m.applyUndo(record), "REORDER 自 Q0/P0 起由模型应用");
        assertEquals(beforeOrder, m.topLevel(), "撤销后数据序应逐元素回到原样");
        assertEquals(List.of(a), m.applyUndoAffected(), "REORDER 的受影响条目 = 被拖条目");
    }

    /**
     * REORDER 正向（整组移动 {@code MoveGroup}）：{@code affected} 为空（成员父组没变）、
     * 撤销载荷不携带条目，撤销后组间顺序回原样。
     */
    @Test
    void applyResolvedDropMovesWholeGroupWithoutAffectedItemsAndUndoRestoresOrder() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        GroupItem<PreviewItem> g1 = m.group(List.of(a));
        GroupItem<PreviewItem> g2 = m.group(List.of(b));
        assertNotNull(g1);
        assertNotNull(g2);
        List<Object> beforeOrder = m.topLevel();
        assertEquals(List.of(g1, g2), beforeOrder, "前置：g1 在 g2 之前");

        WorkspaceModel.DropResult drop = m.applyResolvedDrop(new DropResolver.MoveGroup(g1, 2));

        assertTrue(drop.changed());
        assertEquals(List.of(g2, g1), m.topLevel(), "整组移动应只改顶层组间顺序");
        assertTrue(drop.affected().isEmpty(), "整组移动不改变任何成员的父组 → affected 为空");
        assertEquals(g1, a.parentGroup, "成员父组指针应仍指向 g1");
        assertSame(g1, a.parentGroup);

        UndoManager.Record<PreviewItem> record = m.popRecord();
        assertNotNull(record);
        assertTrue(m.applyUndo(record));
        assertEquals(beforeOrder, m.topLevel(), "撤销后组间顺序应回原样");
    }

    /**
     * MERGE_GROUPS 正向 + 撤销：两组合并后，撤销能把**被拖组**（含其成员副本）按原索引放回、
     * 目标组成员也回原样，且组对象**复用**（组名/组参数保留，不是新对象）。
     */
    @Test
    void applyResolvedDropMergesGroupsAndUndoRestoresDraggedGroupObjectAtOriginalIndex() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        m.addItems(List.of(a, b, c));
        GroupItem<PreviewItem> g1 = m.group(List.of(a, b));
        GroupItem<PreviewItem> g2 = m.group(List.of(c));
        assertNotNull(g1);
        assertNotNull(g2);
        var g1Params = new com.mhtextractor.model.AppConfig();
        g1.setTextParamsCache(g1Params);
        List<Object> beforeOrder = m.topLevel();
        int g1Index = beforeOrder.indexOf(g1);
        int g2Index = beforeOrder.indexOf(g2);

        // 把 g1 整组并入 g2（合并语义：g1 销毁，其成员整体插到目标组 memberIndex 处）
        WorkspaceModel.DropResult drop = m.applyResolvedDrop(new DropResolver.MergeInto(g1, g2, 1));

        assertTrue(drop.changed());
        assertEquals(UndoManager.RecordType.MERGE_GROUPS, drop.type());
        assertSame(g1, drop.merged(), "合并载荷应带回被拖组对象");
        assertSame(g2, drop.mergeTarget());
        assertEquals(g1Index, drop.mergedIndex(), "被拖组的原顶层索引应在改结构之前采集");
        assertEquals(List.of(a, b), drop.mergedMembers(), "被拖组成员副本按原组内顺序");
        assertEquals(List.of(a, b), drop.affected(), "合并的受影响条目 = 被拖组全部成员");
        assertFalse(m.topLevel().contains(g1), "被拖组应从顶层摘除");
        // memberIndex == 1 → 被拖组的成员整体插在目标组原有成员 c 之前（DropResolver 的既有语义，未改）
        assertEquals(List.of(c, a, b), g2.getMembers(), "被拖组成员应插到目标组的 memberIndex 处");
        assertSame(g2, a.parentGroup, "合并后成员父组指针应指向目标组");

        UndoManager.Record<PreviewItem> record = m.popRecord();
        assertNotNull(record);
        assertEquals(UndoManager.RecordType.MERGE_GROUPS, record.type());
        assertSame(g1, record.group(), "撤销记录必须携带被拖组对象（组名/参数随之保留）");
        assertEquals(List.of(a, b), record.items());
        assertEquals(g1Index, record.groupIndex());
        // 撤销记录必须携带"操作前"结构快照 —— 它决定撤销能否回到原结构
        assertEquals(beforeOrder.size(), record.structureBefore().size());

        assertTrue(m.applyUndo(record), "MERGE_GROUPS 自 Q0/P0 起由模型应用");

        assertEquals(beforeOrder, m.topLevel(), "撤销后顶层应逐元素回到原样");
        assertSame(g1, m.topLevel().get(g1Index), "被拖组应复用原对象并回到原顶层索引");
        assertSame(g2, m.topLevel().get(g2Index));
        assertEquals(List.of(a, b), g1.getMembers(), "被拖组成员应回到原组");
        assertEquals(List.of(c), g2.getMembers(), "目标组成员应回原样");
        assertSame(g1, a.parentGroup, "撤销后父组指针应重建为 g1");
        assertSame(g1, b.parentGroup);
        assertSame(g2, c.parentGroup);
        assertSame(g1Params, g1.getTextParamsCache(), "组参数随组对象保留（未被销毁）");
        assertEquals(List.of(a, b), m.applyUndoAffected(), "受影响条目 = 被拖组中 !removed 的成员");
    }

    /**
     * 拖拽把组内最后一个成员拖出顶层 → 原组按 R4 **自动销毁**（{@code DropResolver} 内部行为，
     * 只改结构、**不打日志**：{@code log.workspacemodel.01} 只属于 {@code group(...)} / {@code removeItems(...)}）。
     *
     * <p>本测试只断言**结构事实**，且断言撤销能把该组按原索引整组恢复 —— 那正是"销毁发生在 apply 内部"
     * 这一点的可观测后果。</p>
     */
    @Test
    void applyResolvedDropDestroysEmptiedSourceGroupAndUndoRestoresIt() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        GroupItem<PreviewItem> g = m.group(List.of(a));
        assertNotNull(g);
        var gParams = new com.mhtextractor.model.AppConfig();
        g.setTextParamsCache(gParams);
        List<Object> beforeOrder = m.topLevel();
        int gIndex = beforeOrder.indexOf(g);

        // 把组内唯一成员拖到顶层末尾 → 源组致空 → 自动销毁
        WorkspaceModel.DropResult drop = m.applyResolvedDrop(new DropResolver.InsertTop(a, 2));

        assertTrue(drop.changed());
        assertFalse(m.topLevel().contains(g), "拖出最后一个成员后原组应自动销毁（R4，静默）");
        assertNull(a.parentGroup, "被拖出的条目父组指针应清空");
        assertEquals(List.of(a), drop.affected(), "受影响条目 = 被拖出小组的那一项");

        // 撤销：结构快照里仍持有被销毁的组 → 整组按原索引放回，组参数随对象保留
        UndoManager.Record<PreviewItem> record = m.popRecord();
        assertNotNull(record);
        assertTrue(m.applyUndo(record));
        assertEquals(beforeOrder, m.topLevel(), "撤销后顶层应逐元素回到原样");
        assertSame(g, m.topLevel().get(gIndex), "被自动销毁的组应复用原对象并回到原顶层索引");
        assertEquals(List.of(a), g.getMembers(), "成员应回到原组");
        assertSame(g, a.parentGroup, "撤销后父组指针应重建为原组");
        assertSame(gParams, g.getTextParamsCache(), "组参数随组对象保留");
    }

    /** 原位 no-op：{@code DropResolver.apply} 返回 false 时不得压撤销记录。 */
    @Test
    void applyResolvedDropNoOpDoesNotPushUndoRecord() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        List<Object> beforeOrder = m.topLevel();
        int depthBefore = m.undoDepth();

        // 顶层条目的"插入自身前/后"等价于不动（DropResolver 的原位判定）
        WorkspaceModel.DropResult drop = m.applyResolvedDrop(new DropResolver.InsertTop(a, 1));

        assertFalse(drop.changed(), "原位拖放不得报告结构变化");
        assertEquals(beforeOrder, m.topLevel(), "原位拖放不得改变数据序");
        assertEquals(depthBefore, m.undoDepth(), "无结构变化就不应压撤销记录");
    }

    /** {@code applyResolvedDrop(null)} 是安全 no-op（调用方解析失败时不应改任何状态）。 */
    @Test
    void applyResolvedDropWithNullOperationIsSafeNoOp() {
        WorkspaceModel m = new WorkspaceModel();
        m.addItems(List.of(item("a.mht")));
        int depth = m.undoDepth();

        WorkspaceModel.DropResult drop = m.applyResolvedDrop(null);

        assertFalse(drop.changed());
        assertTrue(drop.affected().isEmpty());
        assertEquals(1, m.topLevel().size());
        assertEquals(depth, m.undoDepth());
    }

    /** {@code topLevel()} 必须返回不可变副本：外部改动不得影响数据序（防"投影"式分叉）。 */
    @Test
    void topLevelReturnsImmutableCopy() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht");
        m.addItems(List.of(a));

        List<Object> view = m.topLevel();
        assertEquals(List.of(a), view);
        assertThrows(UnsupportedOperationException.class, () -> view.add(item("b.mht")),
                "topLevel() 应返回不可变副本（List.copyOf）");
        assertEquals(1, m.topLevel().size(), "外部对返回值的改动不得影响数据序");
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

    // ==================== 勾选 / 组名 / 展开状态（B3） ====================

    /** B3-1：勾选唯一写入口 —— 批量写入生效，且<b>不发布</b>任何变更（静默写入，勾选不触发投影重建）。 */
    @Test
    void setCheckedWritesSilentlyWithoutPublishingChange() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        a.setSelected(true);
        List<WorkspaceChange> events = new ArrayList<>();
        m.addListener(events::add);

        m.setChecked(List.of(a, b), true);
        m.setChecked(List.of(a, b), false);
        m.setChecked(List.of(), true);

        assertFalse(a.selected, "setChecked 应逐条写入目标值");
        assertFalse(b.selected);
        assertTrue(events.isEmpty(),
                "控制组 A：勾选写入是静默的 —— 不发布 WorkspaceChange（发布会被前端当作全量重建投影的信号）");
    }

    /** B3-2：组改名唯一化的唯一来源是模型组名集合（排除自身）；成功后永久退出「未命名组」提醒。 */
    @Test
    void renameGroupUniquifiesAgainstModelGroupNamesAndExitsDefaultNamed() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht"), d = item("d.mht");
        m.addItems(List.of(a, b, c, d));
        GroupItem<PreviewItem> g1 = m.group(List.of(a, b), "组");   // 组1（defaultNamed）
        GroupItem<PreviewItem> g2 = m.group(List.of(c, d), "组");   // 组2（defaultNamed）
        assertTrue(g1.isDefaultNamed() && g2.isDefaultNamed());

        assertEquals("组1(2)", m.renameGroup(g2.getGroupNo(), "组1"),
                "与既有组重名 → 自动追加序号（R7），与 GUI 的 uniqueGroupName(trimmed, otherGroupNames(g)) 一致；返回值为实际生效名");
        assertEquals("组1(2)", g2.getName());
        assertFalse(g2.isDefaultNamed(), "提交改名 → 永久退出「未命名组」提醒（步骤 3 · 功能一）");
        assertTrue(g1.isDefaultNamed(), "改名不影响其它组");

        assertEquals("组1(2)", m.renameGroup(g2.getGroupNo(), "组1(2)"),
                "改回自己当前的名字：排除自身后不冲突，唯一化结果就是原名");
        assertEquals("组1(2)", g2.getName());
    }

    /** B3-2：未知组号的改名是 no-op（不产生任何变更）。 */
    @Test
    void renameGroupOfUnknownGroupNoReturnsNullAndChangesNothing() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        GroupItem<PreviewItem> g = m.group(List.of(a), "组");

        assertNull(m.renameGroup(9999, "组X"), "未知组号 → null 且不产生任何变更");
        assertEquals(1, m.groups().size());
    }

    /**
     * 契约裁决（2026-10-16）：{@code newName == null} 是**前置条件**失败 ——
     * 与 {@code uiport.GroupVm.name} 的非空硬不变量、以及 IPC 侧 {@code newName} INVALID_PARAMS 对齐；
     * 失败必须发生在**任何变更之前**（不写名、不退出「未命名组」提醒）。
     */
    @Test
    void renameGroupRejectsNullNewNameWithoutMutation() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht");
        m.addItems(List.of(a));
        GroupItem<PreviewItem> g = m.group(List.of(a), "组");
        String before = g.getName();
        assertTrue(g.isDefaultNamed());

        assertThrows(NullPointerException.class, () -> m.renameGroup(g.getGroupNo(), null),
                "null 新名违反前置条件（GroupVm 组名非空是硬不变量）");
        assertEquals(before, g.getName(), "前置条件失败 → 组名不变");
        assertTrue(g.isDefaultNamed(), "前置条件失败 → 不退出「未命名组」提醒");
    }

    /** B3-2：展开状态唯一写入口 —— 每调用恰好翻转一次（幂等 = 后端按当前状态原子翻转，快速连点 = 翻过去再翻回来）。 */
    @Test
    void toggleGroupExpandedFlipsExactlyOncePerCall() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht"), c = item("c.mht");
        m.addItems(List.of(a, b, c));
        GroupItem<PreviewItem> g = m.group(List.of(a, b), "组");
        assertTrue(g.isExpanded(), "默认展开");

        assertTrue(m.toggleGroupExpanded(g.getGroupNo()));
        assertFalse(g.isExpanded(), "第一次调用：展开 → 折叠");
        assertFalse(m.displayOrder().contains(a), "折叠后成员离开显示序投影");

        assertTrue(m.toggleGroupExpanded(g.getGroupNo()));
        assertTrue(g.isExpanded(), "第二次调用：折叠 → 展开（控制组 B：若后端实现为恒置 true/恒置 false，此处会红）");
        assertTrue(m.displayOrder().contains(a));

        assertFalse(m.toggleGroupExpanded(9999), "未知组号 → no-op（返回 false）");
        assertTrue(g.isExpanded());
    }

    /** B3-2：组号寻址的拆分组重载 —— 解析后委托 {@link WorkspaceModel#ungroup(GroupItem)}；未知组号 no-op。 */
    @Test
    void ungroupByGroupNoResolvesAndDelegates() {
        WorkspaceModel m = new WorkspaceModel();
        PreviewItem a = item("a.mht"), b = item("b.mht");
        m.addItems(List.of(a, b));
        GroupItem<PreviewItem> g = m.group(List.of(a, b), "组");

        List<PreviewItem> members = m.ungroup(g.getGroupNo());
        assertEquals(List.of(a, b), members, "成员按组内顺序插回顶层");
        assertTrue(m.groups().isEmpty());
        assertEquals(3, m.undoDepth(), "导入 + 建组 + 拆组各压一条撤销记录");
        assertTrue(m.ungroup(g.getGroupNo()).isEmpty(), "组已不在顶层 → no-op（空列表且不产生变更）");
    }
}
