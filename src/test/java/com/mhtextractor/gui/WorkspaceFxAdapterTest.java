package com.mhtextractor.gui;

import com.mhtextractor.app.WorkspaceChange;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.DropResolver;
import com.mhtextractor.util.GroupItem;
import javafx.application.Platform;
import javafx.scene.control.ListView;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link WorkspaceFxAdapter} 的投影一致性测试（Q4/P3，指南 §3.4-1 强制新增）。
 *
 * <p><b>本类为什么住在 {@code com.mhtextractor.gui} 测试包</b>：它按定义要碰 JavaFX
 * （{@code FXCollections} / {@code ListView}）。{@code ArchitectureTest.headlessTestPackagesHaveNoJavaFx}
 * 只扫 {@code com/mhtextractor/baseline} 与 {@code com/mhtextractor/app} 两个测试包，
 * 因此"带 javafx 的投影测试"不能放进那两个包 —— 放这里既满足断言，又让
 * {@code app} 测试包继续保持"无 JavaFX"的证据价值。</p>
 *
 * <p><b>两段证据，分工明确</b>：</p>
 * <ol>
 *   <li>前 6 个测试<b>不需要 FX toolkit</b>（只构造 {@code FXCollections} 的列表 + 桩选择宿主）——
 *       它们证明"命令之后显示序与 {@link WorkspaceModel#displayOrder()} 逐元素一致"，
 *       覆盖 添加 / 移除 / 分组 / 拆组 / 重排 / 撤销；</li>
 *   <li>{@link #listViewShowsExactlyTheModelProjection()} 起一个真实 {@code ListView} 并把断言写在
 *       <b>FX 线程</b>上 —— 它证明"ListView 最终显示与模型一致"（指南原话）。</li>
 * </ol>
 */
class WorkspaceFxAdapterTest {

    private static PreviewItem item(String name) {
        return new PreviewItem("C:/x/" + name, name, LocalDateTime.now(), "C:/x");
    }

    /** 桩选择宿主：把"当前选中"记在一个字段里，用来验证投影前后的选中钉住规则。 */
    private static final class StubHost implements WorkspaceFxAdapter.SelectionHost {
        Object selected;

        @Override
        public Object selectedItem() {
            return selected;
        }

        @Override
        public void select(Object item) {
            selected = item;
        }
    }

    /** 投影后必须与模型的显示序<b>逐元素同一</b>（用身份比较，避免 equals 语义掺进来） */
    private static void assertProjectionMatchesModel(WorkspaceFxAdapter adapter, String what) {
        List<Object> expected = adapter.model().displayOrder();
        List<Object> actual = List.copyOf(adapter.displayItems());
        assertEquals(expected.size(), actual.size(), what + "：显示序长度应等于模型显示序");
        for (int i = 0; i < expected.size(); i++) {
            assertSame(expected.get(i), actual.get(i), what + "：第 " + i + " 项必须与模型显示序同一对象");
        }
    }

    // ==================== 1. 命令之后投影一致（无需 FX toolkit） ====================

    @Test
    void projectionFollowsModelAfterAddRemoveGroupUngroupReorderAndUndo() {
        WorkspaceModel model = new WorkspaceModel();
        WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(model);

        // 添加
        PreviewItem a = item("a"), b = item("b"), c = item("c");
        model.addItems(List.of(a, b, c));
        assertTrue(adapter.isStale(), "命令之后 adapter 必须知道模型变过（订阅证据）");
        adapter.refresh();
        assertFalse(adapter.isStale(), "投影之后不再陈旧");
        assertProjectionMatchesModel(adapter, "添加后");
        assertEquals(3, adapter.displayItems().size());

        // 分组（成员进入组内，显示序变为 组 + 成员 + 顶层项）
        GroupItem<PreviewItem> g = model.group(List.of(a, b));
        assertNotNull(g);
        adapter.refresh();
        assertProjectionMatchesModel(adapter, "分组后");
        assertTrue(adapter.displayItems().contains(g), "组条目必须出现在显示序里");

        // 折叠组：模型显示序过滤掉不可见成员 —— 投影必须跟着变
        g.setExpanded(false);
        adapter.refresh();
        assertProjectionMatchesModel(adapter, "折叠组后");
        assertFalse(adapter.displayItems().contains(a), "折叠组的成员不应出现在显示序");

        // 拆组
        g.setExpanded(true);
        model.ungroup(g);
        adapter.refresh();
        assertProjectionMatchesModel(adapter, "拆组后");

        // 重排（拖放：把 c 插到顶层最前）
        WorkspaceModel.DropResult drop = model.applyResolvedDrop(new DropResolver.InsertTop(c, 0));
        assertTrue(drop.changed(), "前置条件：该拖放应改变结构");
        adapter.refresh();
        assertProjectionMatchesModel(adapter, "重排后");
        assertSame(c, adapter.displayItems().get(0), "重排结果必须体现在显示序首项");

        // 移除（隐藏式）
        model.removeItems(List.of(c));
        adapter.refresh();
        assertProjectionMatchesModel(adapter, "移除后");
        assertFalse(adapter.displayItems().contains(c), "已移除项不得出现在显示序");

        // 撤销（把 c 恢复回来）
        assertTrue(model.undo(), "前置条件：应能撤销一步");
        adapter.refresh();
        assertProjectionMatchesModel(adapter, "撤销后");
        assertTrue(adapter.displayItems().contains(c), "撤销必须把条目带回显示序");
    }

    /** 数据序投影：{@code dataOrder()} 读的是模型实时结构，且只读（{@code clear()} 转发为模型命令）。 */
    @Test
    void dataOrderIsLiveReadOnlyProjectionAndClearForwardsToModel() {
        WorkspaceModel model = new WorkspaceModel();
        WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(model);
        List<Object> view = adapter.dataOrder();

        PreviewItem a = item("a");
        model.addItems(List.of(a));
        assertEquals(List.of(a), view, "dataOrder() 必须实时反映数据序");
        GroupItem<PreviewItem> g = model.group(List.of(a));
        assertEquals(List.of(g), view, "建组之后顶层只剩组条目");

        assertThrows(UnsupportedOperationException.class, () -> view.add(item("b")),
                "dataOrder() 不得可写（模型是唯一写入口）");
        assertThrows(UnsupportedOperationException.class, () -> view.remove(g));

        // 唯一例外：clear() 转发为模型命令（保留 FeatureExercise 的清场入口）
        int depthBefore = model.undoDepth();
        List<WorkspaceChange> seen = new ArrayList<>();
        model.addListener(seen::add);
        view.clear();
        assertTrue(model.topLevel().isEmpty(), "clear() 必须清掉模型的数据序");
        assertEquals(1, seen.size(), "clear() 应恰好发布一次变更");
        assertEquals(WorkspaceChange.Kind.CLEARED, seen.get(0).kind());
        assertEquals(depthBefore, model.undoDepth(), "clearItems() 不得动撤销栈（与既有清场语义一致）");
    }

    /** 选中钉住规则：仍在显示序则恢复，不在则<b>什么都不做</b>（逐字对齐迁移前的 refreshAfterListChange）。 */
    @Test
    void refreshRestoresSelectionOnlyWhenStillVisible() {
        WorkspaceModel model = new WorkspaceModel();
        StubHost host = new StubHost();
        WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(model, host);

        PreviewItem a = item("a"), b = item("b");
        model.addItems(List.of(a, b));
        adapter.refresh();

        // 仍在显示序 → 恢复
        host.selected = b;
        model.addItems(List.of(item("c")));
        adapter.refresh();
        assertSame(b, host.selected, "对象仍在显示序时必须按对象钉回选中");

        // 已不可见 → 不动（不发明"选邻近项"的新规则）
        host.selected = b;
        model.removeItems(List.of(b));
        adapter.refresh();
        assertSame(b, host.selected, "对象不可见时本方法不得改动选中（邻近选择由调用方的具名操作负责）");
        assertFalse(adapter.displayItems().contains(b));
    }

    /** 无宿主（{@link WorkspaceFxAdapter.SelectionHost#NONE}）时只投影、不抛异常。 */
    @Test
    void projectionWorksWithoutSelectionHost() {
        WorkspaceModel model = new WorkspaceModel();
        WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(model);
        model.addItems(List.of(item("a")));
        adapter.refresh();
        assertProjectionMatchesModel(adapter, "无宿主投影后");
        assertEquals(1, adapter.displayItems().size());
    }

    /** 提起动画的瞬时摘除口子：{@code hideRow} 只动显示序，不动模型（P4 随动画整块搬走）。 */
    @Test
    void hideRowOnlyAffectsProjectionNotModel() {
        WorkspaceModel model = new WorkspaceModel();
        WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(model);
        PreviewItem a = item("a"), b = item("b");
        model.addItems(List.of(a, b));
        adapter.refresh();

        int modelCount = model.topLevel().size();
        adapter.hideRow(a);

        assertFalse(adapter.displayItems().contains(a), "被提起的行应从显示序移出");
        assertEquals(modelCount, model.topLevel().size(), "hideRow 不得改数据序");
        adapter.refresh();
        assertProjectionMatchesModel(adapter, "hideRow 之后重新投影");
    }

    /** 退订之后 adapter 不再被通知（{@code isStale()} 保持 false）。 */
    @Test
    void disposeStopsSubscribing() {
        WorkspaceModel model = new WorkspaceModel();
        WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(model);
        adapter.refresh();
        adapter.dispose();

        model.addItems(List.of(item("a")));
        assertFalse(adapter.isStale(), "退订后不得再被通知");
    }

    // ==================== 2. ListView 最终显示与模型一致（FX 线程） ====================

    /**
     * 指南要求的 "FX 线程测试验证 ListView 最终显示与模型一致"。
     *
     * <p>它与上面那批测试的分工：本测试用<b>真实 {@code ListView}</b> 走
     * {@link WorkspaceFxAdapter#bindListView(ListView)}（生产接线方式），断言写在 FX 线程上，
     * 于是"ListView 显示的东西"就是"adapter 的显示序"这一接线本身也被覆盖。</p>
     *
     * <p>⚠️ 与"会自己消失的 UI"同类的坑：{@code setAll} 之后 {@code ListView} 的单元格是<b>下一次
     * pulse</b> 才绑定的，因此断言只取 {@code listView.getItems()}（它就是 adapter 的显示序对象），
     * 不去看已渲染的 cell —— 那需要等 pulse，属 GUI 自动化（zh/en 31/0）与 UiProbe 的覆盖范围。</p>
     */
    @Test
    void listViewShowsExactlyTheModelProjection() throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();

        Thread fx = new Thread(() -> {
            try {
                try {
                    Platform.startup(() -> { });
                } catch (IllegalStateException alreadyStarted) {
                    // FX toolkit 已由同 JVM 的其它用例启动：直接复用，不算失败
                }
                Platform.runLater(() -> {
                    try {
                        runListViewAssertions();
                    } catch (Throwable t) {
                        failure.set(t);
                    } finally {
                        done.countDown();
                    }
                });
            } catch (Throwable t) {
                failure.set(t);
                done.countDown();
            }
        }, "fx-adapter-test");
        fx.setDaemon(true);
        fx.start();

        assertTrue(done.await(60, TimeUnit.SECONDS), "FX 线程用例超时未完成");
        if (failure.get() != null) {
            throw new AssertionError("FX 线程断言失败：" + failure.get(), failure.get());
        }
        Platform.exit();
    }

    private void runListViewAssertions() {
        WorkspaceModel model = new WorkspaceModel();
        WorkspaceFxAdapter adapter = new WorkspaceFxAdapter(model);
        ListView<Object> listView = new ListView<>();
        adapter.bindListView(listView);
        assertSame(adapter.displayItems(), listView.getItems(), "ListView 必须绑定 adapter 的显示序");

        PreviewItem a = item("a"), b = item("b");
        model.addItems(List.of(a, b));
        adapter.refresh();
        List<Object> items = listView.getItems();
        assertEquals(2, items.size());
        assertSame(a, items.get(0));
        assertSame(b, items.get(1));

        // 分组 + 折叠：ListView 里看到的必须等于模型显示序
        GroupItem<PreviewItem> g = model.group(List.of(a, b));
        assertNotNull(g);
        g.setExpanded(false);
        adapter.refresh();
        assertEquals(model.displayOrder(), List.copyOf(listView.getItems()),
                "折叠组后 ListView 列表必须逐元素等于模型显示序");

        // 选中钉住：ListView 的选择模型是 adapter 的选择宿主
        listView.getSelectionModel().select(g);
        model.appendItems(List.of(item("c")));
        adapter.refresh();
        assertSame(g, listView.getSelectionModel().getSelectedItem(),
                "对象仍在显示序时，刷新必须把 ListView 的选中钉回原对象");

        // 数据序入口与 ListView 无关，但同属 adapter 的投影面：写操作必须被挡住
        assertThrows(UnsupportedOperationException.class, () -> adapter.dataOrder().add(item("d")));
        assertProjectionMatchesModel(adapter, "ListView 绑定下投影后");
        assertSame(adapter.displayItems(), listView.getItems());
    }
}
