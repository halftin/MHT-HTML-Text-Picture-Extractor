package com.mhtextractor.gui.controller;

import com.mhtextractor.app.SelectionRules;
import com.mhtextractor.app.WorkspaceModel;
import com.mhtextractor.uicore.WorkspaceView;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.gui.util.DragGeometry;
import com.mhtextractor.app.Texts;
import com.mhtextractor.uicore.GroupRow;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.app.DropResolver;
import com.mhtextractor.uicore.ParamScope;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.geometry.Bounds;
import javafx.scene.image.ImageView;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 列表交互协作者（C2）：列表选择 / 勾选联动 / 拖拽生命周期 / 章节徽标重算从 {@code MainController}
 * <b>原样外迁</b> —— 文案键与日志键（仍是 {@code log.maincontroller.42/43/45}）、线程名、
 * 动画时序（提起/释放 200ms）与选择语义（Shift 锚点区间 / Ctrl 反选 / 双击槽位判定 / 勾选联动抑制标记）
 * 全部保持不变。
 *
 * <p>宿主能力经 {@link Host} 收窄为具名方法：本类不持有 {@code MainController} / {@code MainViewRefs} /
 * FXML namespace，也不得在数据序上直写 —— 数据序唯一所有者是
 * {@link com.mhtextractor.app.WorkspaceModel}，本类只经 {@code dataOrder()} 只读投影与
 * {@code applyResolvedDrop} 语义命令（见 {@code ArchitectureTest.guiDoesNotMutateDataOrderDirectly}）。</p>
 *
 * <p>本类含 JavaFX，属 {@code gui} 层。单元格渲染（{@link PreviewListCell}）经窄接口
 * {@link MainViewActions} 依赖本类，不再依赖 {@code MainController}。</p>
 */
@Slf4j
public final class ListInteractionController implements MainViewActions {

    /**
     * 宿主能力面（C2）：对 {@code MainController} 的全部能力需求收窄为具名方法。
     * 实现由 {@code MainController} 的匿名内部类提供（转发到其保留成员）。
     */
    public interface Host {
        // ---- 控件 ----
        javafx.scene.control.ListView<Object> fileListView();
        javafx.scene.control.CheckBox selectAllCheck();
        javafx.scene.layout.Pane dragOverlay();
        // ---- 模型 / 投影（全部经既有所有者，禁止在数据序上写）----
        List<Object> dataOrder();                                      // = MainController.items（只读投影）
        javafx.collections.ObservableList<Object> displayItems();      // = adapter.displayItems()
        void hideRow(Object item);                                     // = adapter.hideRow(item)
        void applyDrop(Object dragged, Object target, DropResolver.Zone zone, java.util.function.Consumer<com.mhtextractor.uiport.DropVm> finished);
        void rebuildDisplay();                                         // 仍由 MainController 提供（含动画快进）
        // 勾选唯一写入口（B3-1）：= ws.setChecked(...)，本类所有勾选写入都经它，不得直写 PreviewRow.setSelected
        void setChecked(List<PreviewRow> targets, boolean value);
        // ---- 选中读写 ----
        Object currentSelection();
        void selectRow(Object row);
        // ---- 查询 ----
        List<com.mhtextractor.uicore.PreviewRow> allVisibleItems();
        boolean isTextMode();
        AppConfig effectiveForObject(Object item);
        com.mhtextractor.uicore.ParamScope.Status effectiveStatus(Object item);
        int visibleMemberCount(GroupRow<com.mhtextractor.uicore.PreviewRow> group);
        long unitIndex(com.mhtextractor.uicore.PreviewRow item);
        boolean isListAnimating();                                     // C3 动画锁（仍留在 MainController）
        boolean extractionRunning();                                   // 功能一：提取运行中锁定勾选框
        Object animLockedSelection();                                  // 动画锁定的选中项（可 null）
        boolean isCurrentModeConfirmed();
        // ---- 派生刷新 / 邻域动作（实现留在 MainController）----
        void cancelPreviewLoad();                                      // = 端口命令 cancelPreviewLoad()
        void updateListButtons();
        void updateInfoArea(Object sel);
        void updateContentArea(Object sel);
        void updateAdjustCheck(Object sel);
        void updateProgressStatus();                                   // = updateProgressStatus(当前代际)
        void renderThumbnails(com.mhtextractor.uicore.PreviewRow item); // = previewRenderer.renderThumbnails(item)
        void startPreParseFor(List<com.mhtextractor.uicore.PreviewRow> items);
        void clearPreviewCache(com.mhtextractor.uicore.PreviewRow item);
        void toggleGroupExpanded(GroupRow<com.mhtextractor.uicore.PreviewRow> group);
    }

    private final Host host;

    public ListInteractionController(Host host) {
        this.host = host;
    }

    // ===== 选择状态（F1） =====
    /** 全选/全不选批量写入期间的联动抑制标记（F1） */
    private boolean updatingSelection = false;
    private Object lastSelected = null;
    CellIndicator lastDragOverCell = null;
    /** Shift 区间多选的锚点行（显示序元素：PreviewRow 或 GroupRow）；无锚点时 Shift 点击忽略 */
    private Object selectionAnchor = null;

    // ===== 拖拽排序状态 =====
    /** 被拖对象（PreviewRow 或 GroupRow）：null = 无进行中的拖拽会话 */
    Object draggedObject = null;
    boolean dragActive = false;
    boolean dropHandled = false;
    boolean dragPositionStale = false;
    private ParallelTransition liftAnim = null;
    private List<PreviewListCell> liftCells = null;
    private List<ImageView> liftViews = null;

    // ===== MainViewActions：渲染查询（原 PreviewListCell 的 ctl.xxx） =====

    @Override
    public boolean isTextMode() {
        return host.isTextMode();
    }

    @Override
    public AppConfig effectiveForObject(Object item) {
        return host.effectiveForObject(item);
    }

    @Override
    public ParamScope.Status effectiveStatus(Object item) {
        return host.effectiveStatus(item);
    }

    @Override
    public int visibleMemberCount(GroupRow<PreviewRow> group) {
        return host.visibleMemberCount(group);
    }

    @Override
    public long unitIndex(PreviewRow item) {
        return host.unitIndex(item);
    }

    @Override
    public boolean isRowSelected(Object row) {
        return host.currentSelection() == row;
    }

    @Override
    public boolean isListAnimating() {
        return host.isListAnimating();
    }

    @Override
    public boolean isExtractionRunning() {
        return host.extractionRunning();
    }

    @Override
    public void setChecked(List<PreviewRow> targets, boolean value) {
        host.setChecked(targets, value);
    }

    // ===== MainViewActions：选择 / 勾选交互 =====

    /**
     * 行按压的 Ctrl/Shift 多选手势（路线 B 拍板）：只作用于勾选集合，不影响单选选中项。
     * Ctrl+点击 = 反选该行（组行 = 整组全选/全不选切换）；Shift+点击 = 从锚点按显示序
     * 区间勾选（区间内组行 = 整组勾选）；无修饰键 = 维持现状并更新锚点。
     * 动画锁定期间忽略；removed 项不可交互。
     */
    @Override
    public void onCellPressed(Object item, MouseEvent e) {
        // 拖拽状态自愈：真实拖拽期间鼠标被 DnD 手势捕获，按压事件不会到达；
        // 此处仍有 dragActive = 上次拖拽异常残留（如列表外松手后 DONE 丢失）→ 立即恢复显示与状态，
        // 本次按压继续正常处理（数据序未动，cancelDrag 只是重建显示序 + 复位标志）
        if (dragActive) {
            cancelDrag();
        }
        // 仅左键：右键按压不得触发折叠/多选手势（右键由 contextMenuRequested 处理）
        if (host.isListAnimating() || item == null || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (item instanceof GroupRow<?> g) {
            GroupRow<PreviewRow> group = WorkspaceView.asPreviewGroup(g);
            // 箭头槽位（cell x 22-46）：折叠/展开，优先于 Ctrl/Shift 多选手势；
            // pressed 判定比 released 可靠（released 会被 checkbox 吞掉导致间歇丢失）
            if (e.getX() >= 22 && e.getX() <= 46) {
                host.toggleGroupExpanded(group);
                return;
            }
            if (e.isControlDown() && !e.isShiftDown()) {
                onGroupCheckToggled(group);
                selectionAnchor = group;
            } else if (e.isShiftDown() && selectionAnchor != null) {
                applyShiftRange(selectionAnchor, group);
            } else {
                selectionAnchor = group;
            }
            return;
        }
        PreviewRow it = (PreviewRow) item;
        if (it.removed) {
            return;
        }
        if (e.isControlDown() && !e.isShiftDown()) {
            host.setChecked(List.of(it), !it.selected);
            selectionAnchor = it;
            refreshSelectionUi();
            host.updateListButtons();
        } else if (e.isShiftDown() && selectionAnchor != null) {
            applyShiftRange(selectionAnchor, it);
        } else {
            selectionAnchor = it;
        }
    }

    /**
     * Shift 区间勾选/反选（拍板）：按显示序取锚点到当前行区间——区间内全部已完全勾选则取消，
     * 否则全部勾选（项目行 = 自身；组行 = 全部有效成员）。
     */
    private void applyShiftRange(Object anchor, Object current) {
        List<Object> range = SelectionRules.shiftSelectRange(host.displayItems(), anchor, current);
        if (range.isEmpty()) {
            return;
        }
        boolean allChecked = SelectionRules.rangeAllChecked(range, this::isRowFullyChecked);
        boolean target = !allChecked;
        updatingSelection = true;
        try {
            for (Object o : range) {
                setRangeRow(o, target);
            }
        } finally {
            updatingSelection = false;
        }
        selectionAnchor = current;
        refreshSelectionUi();
        host.updateListButtons();
    }

    /** 行的"完全勾选"判定：项目行 = 自身勾选；组行 = 全部有效成员已勾选 */
    private boolean isRowFullyChecked(Object o) {
        if (o instanceof PreviewRow it) {
            return it.selected;
        }
        if (o instanceof GroupRow<?> g) {
            for (Object m : g.getMembers()) {
                if (!(m instanceof PreviewRow p) || (!p.removed && !p.selected)) {
                    return false;
                }
            }
            return true;
        }
        return true;
    }

    /** 区间内行设置勾选值：项目行设自身；组行设全部有效成员（写入一律经勾选唯一写入口） */
    private void setRangeRow(Object o, boolean target) {
        if (o instanceof PreviewRow it && !it.removed) {
            host.setChecked(List.of(it), target);
        } else if (o instanceof GroupRow<?> g) {
            List<PreviewRow> members = new ArrayList<>();
            for (Object m : g.getMembers()) {
                if (m instanceof PreviewRow p && !p.removed) {
                    members.add(p);
                }
            }
            host.setChecked(members, target);
        }
    }

    /**
     * 双击条目 toggle 勾选（拍板）：成员行双击文件名区域（cell x ≥ 50，checkbox 槽之外）
     * 反选自身；组行双击组名区域（cell x ≥ 46，折叠判定区之外）整组反选。
     * 图形交互槽位（复选框/折叠箭头）内双击不触发，避免与单击行为叠加。
     */
    @Override
    public void onCellDoubleClicked(Object item, MouseEvent e) {
        // 仅左键双击（CLICKED 事件在松开后生成，isPrimaryButtonDown 恒为 false，须用 getButton 判定）。
        // 多击时间窗口内 clickCount 连续递增（快速连续双击会得到 3、4、5…），
        // 取偶数次点击（每个双击手势的第二次按下恒为偶数）保证每次双击恰好 toggle 一次。
        if (host.isListAnimating() || item == null || e.getClickCount() < 2 || e.getClickCount() % 2 != 0
                || e.getButton() != MouseButton.PRIMARY) {
            return;
        }
        if (item instanceof GroupRow<?> g) {
            if (e.getX() >= 46) {
                onGroupCheckToggled(WorkspaceView.asPreviewGroup(g));
            }
        } else if (item instanceof PreviewRow it && !it.removed) {
            if (e.getX() >= 50) {
                host.setChecked(List.of(it), !it.selected);
                selectionAnchor = it;
            }
        }
    }

    /** 按当前列表统计三态：有效项 = removed == false 的项目（含组内成员） */
    private SelectionRules.TriState currentSelectionState() {
        List<PreviewRow> vis = host.allVisibleItems();
        int total = vis.size();
        int selected = (int) vis.stream().filter(i -> i.selected).count();
        return SelectionRules.computeState(selected, total);
    }

    /** 列头三态刷新 + 列表单元格（含组复选框）联动刷新 */
    void refreshSelectionUi() {
        SelectionRules.TriState state = currentSelectionState();
        host.selectAllCheck().setIndeterminate(state == SelectionRules.TriState.SOME);
        host.selectAllCheck().setSelected(state == SelectionRules.TriState.ALL);
        host.fileListView().refresh();
    }

    /** 组复选框点击（F1 2.5）：组内全选 → 全不选；全不选或半选 → 全选组内全部成员 */
    @Override
    public void onGroupCheckToggled(GroupRow<PreviewRow> g) {
        if (host.isListAnimating()) {
            return;
        }
        int total = host.visibleMemberCount(g);
        int selected = (int) g.getMembers().stream()
                .filter(m -> !m.removed && m.selected).count();
        boolean target = SelectionRules.computeToggleTarget(
                SelectionRules.computeState(selected, total));
        updatingSelection = true;
        for (PreviewRow m : g.getMembers()) {
            if (!m.removed) {
                host.setChecked(List.of(m), target);
            }
        }
        updatingSelection = false;
        refreshSelectionUi();
        host.updateListButtons();
    }

    /** 列头 CheckBox 点击：当前全不选 → 全选；全选或半选 → 全不选（作用于全部有效项，含组内成员） */
    void onSelectAllToggled() {
        if (updatingSelection || host.isListAnimating()) {
            return;
        }
        boolean target = SelectionRules.computeToggleTarget(currentSelectionState());
        updatingSelection = true;
        host.setChecked(host.allVisibleItems(), target);
        updatingSelection = false;
        refreshSelectionUi();
    }

    /** 单项勾选变化 → 列头三态与组复选框实时联动 */
    void onSelectionChanged() {
        if (!updatingSelection) {
            refreshSelectionUi();
            host.updateListButtons();
        }
    }

    /**
     * 批量勾选写入（C2）：原先散在 onContextSelect / onContextSelectAll / onGroupClicked 里的
     * "updatingSelection=true → 批量 setSelected → finally 复位"段的收口点 ——
     * <b>不</b>做刷新，刷新由调用点显式调 {@link #refreshSelectionUi()}，与搬迁前逐字一致。
     * 写入本身经 {@code Host.setChecked}（B3-1：勾选唯一写入口在后端模型）。
     */
    void setCheckedSilently(List<PreviewRow> targets, boolean value) {
        updatingSelection = true;
        try {
            host.setChecked(targets, value);
        } finally {
            updatingSelection = false;
        }
    }

    // ===== 列表选择联动 =====

    void onItemSelected(Object item) {
        // 折叠/展开动画期间列表选择锁定：选择变化回选至锁定项（选中项一致时不触发回选，防递归）
        if (host.isListAnimating()) {
            Object locked = host.animLockedSelection();
            if (locked != null && host.currentSelection() != locked) {
                host.selectRow(locked);
            }
            return;
        }
        host.cancelPreviewLoad();
        if (lastSelected instanceof PreviewRow last && last != item) {
            // 仅当前文件的图片数据驻留内存（E1.2：只清图片数据的唯一实现是 PreviewRow.releaseImageData，
            // 文本/标题/meta/状态保持不动 —— 这里的语义是"慎重"的局部释放，不是整体重置）
            last.releaseImageData();
        }
        lastSelected = item;
        // 组行选中/未选中背景样式随选择变化切换；拖拽动画期间暂停刷新避免打乱快照画面
        if (!dragActive) {
            host.fileListView().refresh();
        }
        host.updateAdjustCheck(item);
        host.updateInfoArea(item);
        host.updateContentArea(item);
        host.updateListButtons();
    }

    // ===== 章节徽标（R11） =====

    /** 章节徽标重算（R11）：每个组 = 独立提取单元（组内重算），未分组项目按顶层顺序；每文件章节覆盖优先 */
    void recomputeBadges() {
        int top = 1;
        for (Object o : host.dataOrder()) {
            if (o instanceof GroupRow<?> g) {
                int n = 1;
                for (Object m : g.getMembers()) {
                    if (m instanceof PreviewRow it) {
                        n = assignBadge(it, n);
                    }
                }
            } else if (o instanceof PreviewRow it) {
                top = assignBadge(it, top);
            }
        }
        host.fileListView().refresh();
    }

    /** 单项徽标赋值：按该条目生效参数判定章节编号（组参数可独立开关）；章节覆盖关闭或未解析完成时不占编号 */
    private int assignBadge(PreviewRow it, int n) {
        if (it.removed) {
            it.badge = null;
            return n;
        }
        AppConfig eff = host.effectiveForObject(it);
        boolean numbering = host.isTextMode() && eff != null && eff.isAddChapterNumber();
        if (numbering && it.status == PreviewRow.Status.READY && it.chapterEnabled) {
            it.badge = String.valueOf(n);
            return n + 1;
        }
        it.badge = null;
        return n;
    }

    // ===== 拖拽排序（阶段 7 6.6） =====

    /**
     * 拖放核心执行（阶段 7 6.6 拖拽规则）：落点解析 → 数据序修改 → REORDER/MERGE_GROUPS 入撤销栈 →
     * 释放动画 → 父组变化项目重新预解析（生效参数切换，阶段 5 机制）。
     * cell 非空 = 真实 DnD 路径（含动画）；cell 为 null = 程序化演练路径（跳过动画）。
     */
    @Override
    public boolean performDrop(Object dragged, Object target, DropResolver.Zone zone,
                               PreviewListCell cell) {
        if (dragged == null || target == null || (target instanceof PreviewRow it && it.removed)) {
            return false;
        }

        // 立即结束提起动画（若仍在进行），快照按当前实际位置重新采集
        if (cell != null) {
            finishLift();
        }
        double gapTopY = 0;
        if (cell != null) {
            gapTopY = DragGeometry.cellBoundsInOverlay(cell, host.dragOverlay()).getMinY()
                    + (zone == DropResolver.Zone.TOP ? 0 : cell.getHeight());
        }
        final double gap = gapTopY;
        host.applyDrop(dragged,target,zone,drop -> {
            if (!drop.changed()) {cancelDrag();return;}
            if (cell != null) animateReleaseInsert(dragged,gap);
            else {host.rebuildDisplay();finishDragReset();}
        });
        return true;
    }

    /** 已解析操作的执行核心：快照 → apply → 撤销记录（均由模型负责）→ 动画/瞬时重建 → 父组变化重解析 */


    // ==================== 拖拽排序动画 ====================

    PreviewListCell findCell(Object item) {
        for (javafx.scene.Node n : host.fileListView().lookupAll(".list-cell")) {
            if (n instanceof PreviewListCell c && c.getItem() == item) {
                return c;
            }
        }
        return null;
    }

    /** 提起：被拖条目从显示序瞬间移除（数据序不动），下方条目快照上滑 0.2s 补位 */
    @Override
    public void beginDrag(PreviewListCell sourceCell) {
        draggedObject = sourceCell.getItem();
        dragPositionStale = false;
        dragActive = true;
        double dragGapTopY = DragGeometry.cellBoundsInOverlay(sourceCell, host.dragOverlay()).getMinY();
        double rowHeight = sourceCell.getHeight();

        List<PreviewListCell> cells = new ArrayList<>();
        List<ImageView> views = new ArrayList<>();
        try {
            for (javafx.scene.Node n : host.fileListView().lookupAll(".list-cell")) {
                if (n instanceof PreviewListCell c && c != sourceCell && c.getItem() != null
                        && !(c.getItem() instanceof PreviewRow it && it.removed)) {
                    Bounds b = DragGeometry.cellBoundsInOverlay(c, host.dragOverlay());
                    if (b.getMinY() >= dragGapTopY - 1) {
                        cells.add(c);
                        ImageView iv = new ImageView(c.snapshot(DragGeometry.transparentSnapParams(), null));
                        iv.setLayoutX(b.getMinX());
                        iv.setLayoutY(b.getMinY());
                        host.dragOverlay().getChildren().add(iv);
                        views.add(iv);
                    }
                }
            }
        } catch (Exception ex) {
            // 快照失败兜底：瞬时重排复位（cancelDrag 会清除 dragActive，防卡死）
            log.warn(Texts.msg("log.maincontroller.43", ex.getMessage()), ex);
            host.dragOverlay().getChildren().removeAll(views);
            cancelDrag();
            return;
        }

        host.hideRow(draggedObject);
        cells.forEach(c -> c.setVisible(false));

        liftCells = cells;
        liftViews = views;
        liftAnim = new ParallelTransition();
        for (ImageView iv : views) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), iv);
            tt.setByY(-rowHeight);
            liftAnim.getChildren().add(tt);
        }
        liftAnim.setOnFinished(_ -> finishLift());
        liftAnim.play();
    }

    /** 结束提起动画：清除快照、恢复单元格显示 */
    void finishLift() {
        if (liftAnim != null) {
            liftAnim.stop();
            liftAnim = null;
        }
        if (liftViews != null) {
            host.dragOverlay().getChildren().removeAll(liftViews);
            liftViews = null;
        }
        if (liftCells != null) {
            liftCells.forEach(c -> c.setVisible(true));
            liftCells = null;
        }
    }

    /**
     * 松手/取消共用：调用前数据序已就绪（松手路径由调用方调整，取消路径未动），
     * 重建显示序让条目出现在目标行（静态快照置于最上层），下方条目快照从其底下下滑 0.2s 让位
     */
    private void animateReleaseInsert(Object item, double gapTopY) {
        double rowHeight = 0;
        List<PreviewListCell> cells = new ArrayList<>();
        List<ImageView> views = new ArrayList<>();
        try {
            for (javafx.scene.Node n : host.fileListView().lookupAll(".list-cell")) {
                if (n instanceof PreviewListCell c && c.getItem() != null
                        && !(c.getItem() instanceof PreviewRow it && it.removed)) {
                    Bounds b = DragGeometry.cellBoundsInOverlay(c, host.dragOverlay());
                    if (b.getMinY() >= gapTopY - 1) {
                        cells.add(c);
                        ImageView iv = new ImageView(c.snapshot(DragGeometry.transparentSnapParams(), null));
                        iv.setLayoutX(b.getMinX());
                        iv.setLayoutY(b.getMinY());
                        host.dragOverlay().getChildren().add(iv);
                        views.add(iv);
                        rowHeight = c.getHeight();
                    }
                }
            }
        } catch (Exception ex) {
            log.warn(Texts.msg("log.maincontroller.43", ex.getMessage()), ex);
            host.dragOverlay().getChildren().removeAll(views);
            host.rebuildDisplay();
            finishDragReset();
            return;
        }

        if (rowHeight <= 0) {
            rowHeight = 39;
        }

        host.rebuildDisplay();
        // 组合并后被拖组已不在显示序中：仅当对象仍存在时选中（避免 ListView.select 找不到对象）
        if (host.displayItems().contains(item)) {
            host.selectRow(item);
        }
        // 同步完成单元格绑定，保证后续快照/隐藏准确
        host.fileListView().layout();
        cells.forEach(c -> c.setVisible(false));
        PreviewListCell itemCell = findCell(item);
        if (itemCell != null) {
            // 条目行快照最后加入覆盖层（位于最上层、静态不动），下方快照下滑时从其底下钻过
            try {
                Bounds ib = DragGeometry.cellBoundsInOverlay(itemCell, host.dragOverlay());
                ImageView itemView = new ImageView(itemCell.snapshot(DragGeometry.transparentSnapParams(), null));
                itemView.setLayoutX(ib.getMinX());
                itemView.setLayoutY(ib.getMinY());
                host.dragOverlay().getChildren().add(itemView);
            } catch (Exception ex) {
                log.warn(Texts.msg("log.maincontroller.45", ex.getMessage()), ex);
            }
            itemCell.setVisible(false);
        }

        ParallelTransition pt = new ParallelTransition();
        for (ImageView iv : views) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), iv);
            tt.setByY(rowHeight);
            pt.getChildren().add(tt);
        }
        pt.setOnFinished(_ -> {
            host.dragOverlay().getChildren().clear();
            cells.forEach(c -> c.setVisible(true));
            if (itemCell != null) {
                itemCell.setVisible(true);
            }
            finishDragReset();
        });
        pt.play();
    }

    /** 拖拽全流程结束：复位状态并重算徽标/信息区/按钮/进度 */
    private void finishDragReset() {
        draggedObject = null;
        dragActive = false;
        dropHandled = false;
        onOrderChanged();
    }

    /** 取消拖拽：瞬时恢复到原显示位置（数据序未动，重建显示序即可）。
     *  曾用快照释放动画，但列表外松手的恢复路径存在间歇性丢失（条目消失），改为瞬时恢复保证可靠 */
    @Override
    public void cancelDrag() {
        finishLift();
        host.rebuildDisplay();
        finishDragReset();
    }

    private void onOrderChanged() {
        recomputeBadges();
        host.updateListButtons();
        host.updateProgressStatus();
        Object sel = host.currentSelection();
        host.updateInfoArea(sel);
        if (!host.isTextMode() && sel instanceof PreviewRow it && it.cachedThumbs != null) {
            host.renderThumbnails(it);
        }
    }

    // ===== MainViewActions：拖拽会话状态读写 =====

    @Override
    public boolean isDragActive() {
        return dragActive;
    }

    @Override
    public Object draggedObject() {
        return draggedObject;
    }

    @Override
    public List<Object> dataOrder() {
        return host.dataOrder();
    }

    @Override
    public boolean isDropHandled() {
        return dropHandled;
    }

    @Override
    public CellIndicator lastDragOverCell() {
        return lastDragOverCell;
    }

    @Override
    public void setLastDragOverCell(CellIndicator cell) {
        lastDragOverCell = cell;
    }

    @Override
    public void markDropHandled() {
        dropHandled = true;
    }

    // ===== 包级 API（MainController / MainViewInstaller 调用，命名即语义） =====

    /** 拖拽动画期间列表滚动 → 快照位置失效（MainViewInstaller 的滚动监听调用） */
    void markDragPositionStale() {
        dragPositionStale = true;
    }

    boolean isDragPositionStale() {
        return dragPositionStale;
    }
}
