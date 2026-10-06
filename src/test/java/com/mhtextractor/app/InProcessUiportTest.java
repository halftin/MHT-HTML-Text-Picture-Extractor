package com.mhtextractor.app;

import com.mhtextractor.model.GroupItem;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.uiport.Confirmer;
import com.mhtextractor.uiport.ConfirmToken;
import com.mhtextractor.uiport.GroupVm;
import com.mhtextractor.uiport.ItemVm;
import com.mhtextractor.uiport.PortEvent;
import com.mhtextractor.uiport.TriState;
import com.mhtextractor.uiport.UpdateKind;
import com.mhtextractor.uiport.WorkspaceVm;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P3-3a 进程内端口适配器（{@link InProcessUiport}）的无头单测。
 *
 * <p>用真实 {@link WorkspaceModel} + 桩 {@link Confirmer}（卡片是前端的事），只断言
 * <b>适配器自身</b>的桥接语义：视图模型组装、命令转发、事件桥接与退订、确认器注入、
 * 快照不缓存。不复制 {@code WorkspaceModelTest} 的模型断言。零 JavaFX。</p>
 */
class InProcessUiportTest {

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
    }

    /** 桩确认器：本测试不触确认卡片语义，仅验证注入与透传。 */
    private static Confirmer stubConfirmer() {
        return request -> new ConfirmToken() {
            @Override
            public void answer(String actionKey) {
            }

            @Override
            public void cancel() {
            }
        };
    }

    @Test
    void importedItemsAreExposedAsViewModel() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem a = item("a.mht");
        a.setSelected(true);
        PreviewItem b = item("b.mht");
        model.addItems(List.of(a, b));

        WorkspaceVm vm = uiport.queries().workspace();
        assertEquals(2, vm.topLevelItems().size());
        ItemVm first = vm.topLevelItems().get(0);
        assertEquals(a.path, first.id());
        assertEquals("a.mht", first.name());
        assertTrue(first.checked(), "checked 映射 item.selected");
        assertFalse(first.removed());
        assertEquals(0, first.unitIndex());
        assertEquals(UpdateKind.NONE, first.update());
        assertEquals(1, vm.topLevelItems().get(1).unitIndex());
        assertTrue(vm.groups().isEmpty());
        assertTrue(vm.canUndo(), "addItems 压入 IMPORT 撤销记录");
        assertEquals(1, vm.undoDepth());
        assertEquals(TriState.SOME, vm.selection(), "2 条可见中 1 条勾选 = SOME");
    }

    @Test
    void undoCommandReducesUndoDepthAndRemovesItems() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        model.addItems(List.of(item("a.mht"), item("b.mht")));
        assertTrue(uiport.queries().workspace().canUndo());

        uiport.commands().undo();

        WorkspaceVm vm = uiport.queries().workspace();
        assertTrue(vm.topLevelItems().isEmpty(), "撤销 IMPORT 把条目移出数据序");
        assertFalse(vm.canUndo());
        assertEquals(0, vm.undoDepth());
    }

    @Test
    void eventsDeliverWorkspaceChangedAndCloseUnsubscribes() throws Exception {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        List<PortEvent> received = new ArrayList<>();
        AutoCloseable subscription = uiport.events().subscribe(received::add);

        model.addItems(List.of(item("a.mht")));
        assertEquals(1, received.size());
        assertEquals("ITEMS_ADDED", ((PortEvent.WorkspaceChanged) received.get(0)).kind(),
                "WorkspaceChange.Kind 映射为 WorkspaceChanged(kind.name())");

        subscription.close();
        subscription.close();   // 幂等：重复退订是 no-op
        model.addItems(List.of(item("b.mht")));
        assertEquals(1, received.size(),
                "控制组 B：close() 退订后再改模型不再收到事件（事件桥接可退订）");
    }

    @Test
    void clearItemsCommandEmitsClearedAndEmptyView() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        model.addItems(List.of(item("a.mht")));
        List<PortEvent> received = new ArrayList<>();
        uiport.events().subscribe(received::add);

        uiport.commands().clearItems();

        assertTrue(uiport.queries().workspace().topLevelItems().isEmpty());
        assertEquals("CLEARED",
                ((PortEvent.WorkspaceChanged) received.get(received.size() - 1)).kind());
    }

    @Test
    void workspaceSnapshotIsNotCached() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        model.addItems(List.of(item("a.mht")));

        WorkspaceVm first = uiport.queries().workspace();
        assertEquals(1, first.topLevelItems().size());

        model.clearItems();   // 中途改模型（不缓存的前提）

        WorkspaceVm second = uiport.queries().workspace();
        assertTrue(second.topLevelItems().isEmpty(),
                "控制组 A：适配器不缓存 —— 连续两次 workspace() 中途改模型（clearItems），第二次必须反映变化（证明非缓存）");
    }

    @Test
    void groupsExposeNumberNameExpandedDefaultNamedAndMembers() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem a = item("a.mht"), b = item("b.mht");
        model.addItems(List.of(a, b));
        model.group(List.of(a, b), "组");

        WorkspaceVm vm = uiport.queries().workspace();
        assertTrue(vm.topLevelItems().isEmpty(), "成员入组后不再出现在顶层条目里");
        assertEquals(1, vm.groups().size());
        GroupVm gv = vm.groups().get(0);
        assertEquals(1, gv.no());
        assertEquals("组1", gv.name());
        assertTrue(gv.expanded());
        assertTrue(gv.defaultNamed(), "手工建组 = 默认名（未命名组拦截标记）");
        assertEquals(2, gv.members().size());
        assertEquals(0, gv.members().get(0).unitIndex(), "组内成员按组内可见成员计数（R10）");
        assertEquals(1, gv.members().get(1).unitIndex());
    }

    @Test
    void removedTopLevelItemsStayInSnapshotWithFlagAndNullUnitIndex() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem a = item("a.mht"), b = item("b.mht");
        model.addItems(List.of(a, b));
        model.removeItems(List.of(a));

        WorkspaceVm vm = uiport.queries().workspace();
        assertEquals(2, vm.topLevelItems().size(),
                "隐藏式移除：条目不实际从数据序删除，快照仍含已移除条目（removed=true，由前端隐藏）");
        ItemVm ra = vm.topLevelItems().get(0);
        assertTrue(ra.removed());
        assertNull(ra.unitIndex(), "已移除条目未参与显示与序号 → unitIndex = null");
        assertEquals(0, vm.topLevelItems().get(1).unitIndex());
        assertEquals(TriState.NONE, vm.selection());
    }

    @Test
    void removedGroupMemberStaysInSnapshotWithFlagAndNullUnitIndex() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem a = item("a.mht"), b = item("b.mht");
        model.addItems(List.of(a, b));
        model.group(List.of(a, b), "组");
        model.removeItems(List.of(a));

        WorkspaceVm vm = uiport.queries().workspace();
        GroupVm gv = vm.groups().get(0);
        assertEquals(2, gv.members().size(), "隐藏式移除不实际从组内删除成员");
        assertTrue(gv.members().get(0).removed());
        assertNull(gv.members().get(0).unitIndex());
        assertEquals(0, gv.members().get(1).unitIndex(), "组内可见序号跳过已移除成员");
        assertEquals(TriState.NONE, vm.selection());
    }

    @Test
    void unitIndexOfTopLevelItemCountsExpandedGroupMembersBeforeIt() {
        WorkspaceModel model = new WorkspaceModel();
        InProcessUiport uiport = new InProcessUiport(model, stubConfirmer());
        PreviewItem a = item("a.mht"), b = item("b.mht"),
                c = item("c.mht"), d = item("d.mht");
        model.addItems(List.of(a, b, c, d));
        model.group(List.of(b, c), "组");   // 显示序 = [a, 组, b, c, d]

        WorkspaceVm vm = uiport.queries().workspace();
        assertEquals(2, vm.topLevelItems().size());
        assertEquals(0, vm.topLevelItems().get(0).unitIndex());   // a
        assertEquals(3, vm.topLevelItems().get(1).unitIndex(),
                "R10：顶层条目按显示序中排在它前面的可见条目计数（含展开组成员 a,b,c → d=3）");
    }

    @Test
    void confirmerIsTheInjectedInstance() {
        WorkspaceModel model = new WorkspaceModel();
        Confirmer stub = stubConfirmer();
        InProcessUiport uiport = new InProcessUiport(model, stub);
        assertSame(stub, uiport.confirmer(), "确认往返由前端注入，适配器原样透传（本类不实现卡片）");
    }
}
