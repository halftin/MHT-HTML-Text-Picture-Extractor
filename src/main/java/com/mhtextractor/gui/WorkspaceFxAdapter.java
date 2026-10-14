package com.mhtextractor.gui;

import com.mhtextractor.app.WorkspaceChange;
import com.mhtextractor.uicore.WorkspaceView;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.ListView;

import java.util.AbstractList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 工作区模型的 **JavaFX 投影层**（Q4 / P3）—— 「模型唯一拥有业务数据，JavaFX 列表是它的投影」的落地处。
 *
 * <p><b>它拥有两份投影</b>（都在 FX 线程使用）：</p>
 * <ol>
 *   <li>{@link #displayItems()} —— <b>显示序</b>（{@code ListView} 直接绑定的那张展平表）。
 *       它是本类<b>唯一维护</b>的可变列表：{@link #project()} 用
 *       {@link WorkspaceView#displayOrder()} 一次性 {@code setAll}（第一阶段只做全量投影，
 *       增量 diff 要等"测量证明 setAll 有性能问题"才做 —— 指南明令）；</li>
 *   <li>{@link #dataOrder()} —— <b>数据序只读投影</b>：读直达模型内部存储
 *       （{@link WorkspaceView#topLevelView()}，零拷贝实时视图），任何写操作抛
 *       {@code UnsupportedOperationException}，<b>唯一例外</b>是 {@code clear()} ——
 *       它转发为模型命令 {@link WorkspaceView#clearItems()}（"清场只清列表"的既有语义）。
 *       保留这个入口是为了让 {@code FeatureExercise}（P7 才改造）继续以 {@code mc.items} 驱动演练，
 *       同时不给 GUI 留下任何"绕过模型写数据序"的路。</li>
 * </ol>
 *
 * <p><b>🚩 本步的取舍：订阅变更，但<b>不</b>在每次变更上自动投影（逐条给理由）</b></p>
 * <p>指南对 adapter 的要求是"订阅 {@link WorkspaceChange}，只在 FX 线程应用"，
 * 并<b>同时</b>明令"不要在动画流程中重复发布中间快照"。这两条在本步的代码事实下会互相打架：</p>
 * <ol>
 *   <li><b>拖拽动画的时序会坏</b>：{@code performResolvedDrop} 先改数据序、再由
 *       {@code animateReleaseInsert} <b>先拍旧布局快照</b>、随后才重建显示序并让快照滑到新位置。
 *       若 adapter 在 {@code applyResolvedDrop} 那一刻就 setAll，动画拍到的"旧布局"已是新布局
 *       —— 视觉上就是"没有让位动画"。这正是指南警告的那件事。</li>
 *   <li><b>会重新引入导入路径刻意避免的 O(批次数 × n)</b>：{@code WorkspaceImportCoordinator}
 *       每轮只刷一次列表（RISK-8，不是每批一次）；逐变更自动投影会把这条优化抹掉。</li>
 *   <li><b>选中抖动</b>：{@code setAll} 会重置 {@code ListView} 的选择模型，再按对象钉回。
 *       每次结构命令多一次"清选择 → 重选"，等于把选中监听回调翻倍，属可观察行为变化。</li>
 * </ol>
 * <p>因此本步的实现是：adapter <b>订阅</b>变更（{@link #isStale()} 可查"自上次投影以来模型是否变过"），
 * 而<b>投影时机仍由调用方显式给出</b>（{@link #project()} / {@link #refresh()}），调用点与迁移前的
 * {@code rebuildDisplay()} / {@code refreshAfterListChange()} 一一对应 —— 于是行为逐字保持，
 * 而"谁写显示序"已经收口到本类。P4 把动画整块搬进来时，再打开"变更即投影"（那时不再有中间快照问题）。</p>
 *
 * <p><b>线程约定（FX 线程守卫已落地）</b>：{@link #project()} / {@link #refresh()} /
 * {@link #bindListView(ListView)} 的生产调用点全在 FX 线程（{@code initialize}、命令回调、
 * {@code refreshAfterListChange}）。守卫在<b>投产接线点</b>生效：{@link #bindListView(ListView)}
 * 校验自身在 FX 线程（绑定 ListView 是场景图写操作）并把守卫<b>打开</b>；此后
 * {@link #project()} / {@link #refresh()} 在非 FX 线程调用即抛 {@link IllegalStateException}
 * （消息指明方法名）。未接线（无头投影测试）不守卫 —— 测试不必起 FX toolkit。</p>
 */
public final class WorkspaceFxAdapter {

    /**
     * 选择/焦点宿主：生产是 {@code ListView} 的选择模型，测试可注入桩。
     *
     * <p><b>为什么不让 adapter 直接持有 {@code ListView}</b>：投影一致性测试不该被"必须起 FX toolkit"
     * 绑住（打包机可能没有显示器），而"恢复选中"这条规则本身只依赖"读当前选中 / 写选中"两个操作。</p>
     */
    public interface SelectionHost {

        /** 投影前的选中对象（可为 null） */
        Object selectedItem();

        /** 投影后按对象钉住选中 */
        void select(Object item);

        /** 无宿主：只投影、不恢复选中 */
        SelectionHost NONE = new SelectionHost() {
            @Override
            public Object selectedItem() {
                return null;
            }

            @Override
            public void select(Object item) {
                // 无宿主：不恢复选中
            }
        };
    }

    private final WorkspaceView model;

    /** 显示序（ListView 直接绑定）—— 本类独占维护 */
    private final ObservableList<Object> displayItems = FXCollections.observableArrayList();

    /** 数据序只读投影 —— 见类 javadoc 的第 2 条 */
    private final List<Object> dataOrder;

    /** 订阅句柄（存字段而不是每次 {@code this::onChange}：{@code removeListener} 必须能退掉同一个实例） */
    private final Consumer<WorkspaceChange> listener = this::onChange;

    /** 选择宿主；默认 {@link SelectionHost#NONE} */
    private SelectionHost host = SelectionHost.NONE;

    /** 自上次投影以来模型是否发布过变更（订阅的可观察产物；P4 打开自动投影后用它做合并刷新） */
    private boolean stale = false;
    private long projectedVersion;

    /** bindListView 之后 project()/refresh() 的 FX 线程守卫开关（只有投产接线点置 true；未接线不守卫，无头投影测试不必起 toolkit） */
    private boolean fxThreadGuarded = false;

    /**
     * @param model 被投影的模型（非 null）
     */
    public WorkspaceFxAdapter(WorkspaceView model) {
        this(model, SelectionHost.NONE);
    }

    /**
     * @param model 被投影的模型（非 null）
     * @param host  选择宿主（null 视为 {@link SelectionHost#NONE}）
     */
    public WorkspaceFxAdapter(WorkspaceView model, SelectionHost host) {
        this.model = model;
        this.host = host == null ? SelectionHost.NONE : host;
        // 数据序只读投影：AbstractList 只要求 get/size；add/remove/set 由基类抛 UnsupportedOperationException
        this.dataOrder = new AbstractList<>() {
            @Override
            public Object get(int index) {
                return model.topLevelView().get(index);
            }

            @Override
            public int size() {
                return model.topLevelView().size();
            }

            @Override
            public void clear() {
                model.clearItems();
            }
        };

    }

    /** 被投影的模型 */
    public WorkspaceView model() {
        return model;
    }

    /** 显示序：{@code ListView} 直接绑定它；只由本类的 {@link #project()} / {@link #hideRow(Object)} 维护 */
    public ObservableList<Object> displayItems() {
        return displayItems;
    }

    /** 数据序只读投影（{@code MainController.items} 的来源；写操作抛异常，{@code clear()} 转发为模型命令） */
    public List<Object> dataOrder() {
        return dataOrder;
    }

    /** 自上次投影以来模型是否发布过变更（订阅证据，也是 P4 自动投影的合并依据） */
    public boolean isStale() {
        return projectedVersion != model.version();
    }

    /** 绑定选择宿主（生产：{@code ListView} 的选择模型） */
    public void bindSelection(SelectionHost host) {
        this.host = host == null ? SelectionHost.NONE : host;
    }

    /**
     * 绑定 {@code ListView}：把 {@link #displayItems()} 交给它渲染，并以它的选择模型为选择宿主。
     *
     * <p>调用点是 {@code MainController.initialize()}（FX 线程）。绑定之后
     * "ListView 显示的东西"与"本类的显示序"是<b>同一个列表对象</b>，
     * 因此"ListView 最终显示与模型一致"等价于"displayItems 与 {@code model.displayOrder()} 一致"。</p>
     */
    public void bindListView(ListView<Object> listView) {
        if (!Platform.isFxApplicationThread()) {
            throw new IllegalStateException("WorkspaceFxAdapter.bindListView 必须在 FX 线程调用（绑定 ListView 是场景图写操作）");
        }
        listView.setItems(displayItems);
        bindSelection(new SelectionHost() {
            @Override
            public Object selectedItem() {
                return listView.getSelectionModel().getSelectedItem();
            }

            @Override
            public void select(Object item) {
                listView.getSelectionModel().select(item);
            }
        });
        fxThreadGuarded = true;
    }

    /** 退订（宿主窗口销毁时用；未订阅是 no-op） */
    public void dispose() {
        model.dispose();
    }

    /**
     * <b>全量投影</b>：把显示序一次性对齐到 {@link WorkspaceView#displayOrder()}，
     * <b>不</b>碰选中（对应迁移前的 {@code MainController.rebuildDisplay()} 的那一行 {@code setAll}）。
     *
     * <p>动画路径（提起/松手/展开折叠）用它自己那一套：它们需要"什么时候重建"由动画掌握，
     * 见类 javadoc 的取舍说明。</p>
     */
    public void project() {
        assertFxThread("project");
        displayItems.setAll(model.displayOrder());
        projectedVersion=model.version();
        stale = false;
    }

    /**
     * <b>投影 + 按对象钉住选中</b>：逐字对齐迁移前 {@code refreshAfterListChange} 的头部三行 ——
     *
     * <pre>
     * Object sel = fileListView.getSelectionModel().getSelectedItem();
     * rebuildDisplay();
     * if (sel != null &amp;&amp; displayItems.contains(sel)) fileListView.getSelectionModel().select(sel);
     * </pre>
     *
     * <p>⚠️ <b>不发明新规则</b>：选中对象已不可见时本方法<b>什么都不做</b>（迁移前也是如此）——
     * "选中邻近项"由调用方的具名操作负责（如 {@code selectNearestVisible}），不在这里兜底。</p>
     */
    public void refresh() {
        assertFxThread("refresh");
        Object sel = host.selectedItem();
        project();
        if (sel != null && displayItems.contains(sel)) {
            host.select(sel);
        }
    }

    /**
     * FX 线程守卫：仅当 {@link #bindListView(ListView)}（投产接线点）已打开守卫时生效。
     * 未接线（无头投影测试）不守卫 —— 也让 {@code Platform.isFxApplicationThread()}（会尝试
     * 初始化 toolkit）绝不在无头路径上被求值。
     */
    private void assertFxThread(String method) {
        if (fxThreadGuarded && !Platform.isFxApplicationThread()) {
            throw new IllegalStateException("WorkspaceFxAdapter." + method + " 必须在 FX 线程调用（已 bindListView）");
        }
    }

    /**
     * 从显示序里<b>瞬时摘除</b>一行：提起动画用（拖动中的行由覆盖层快照顶替，真实行先移出列表）。
     *
     * <p>这是"显示序写入收口到 adapter"后留下的唯一一个动画专用口子；P4 把动画整块搬进来时它应被吸收进
     * 动画实现，而不是继续作为公开入口存在。</p>
     */
    public void hideRow(Object item) {
        displayItems.remove(item);
    }

    /** 订阅回调：只记"脏"，不投影（理由见类 javadoc） */
    private void onChange(WorkspaceChange change) {
        stale = true;
    }
}
