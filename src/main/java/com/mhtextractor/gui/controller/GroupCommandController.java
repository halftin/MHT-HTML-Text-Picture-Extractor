package com.mhtextractor.gui.controller;

import com.mhtextractor.app.WorkspaceStructure;
import com.mhtextractor.gui.util.DragGeometry;
import com.mhtextractor.i18n.I18n;
import com.mhtextractor.model.PreviewItem;
import com.mhtextractor.util.GroupItem;
import javafx.animation.Interpolator;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.collections.ObservableList;
import javafx.geometry.Bounds;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.Pane;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 分组命令协作者（C3a）：分组/拆组命令入口与组条目折叠/展开动画的状态和实现从 {@code MainController}
 * <b>原样外迁</b> —— 文案键与日志键（仍是 {@code log.maincontroller.56/57/58/59}）、动画时长
 * （{@code Duration.millis(200)} ease-out）与几何快照语义全部保持不变。
 *
 * <p>宿主能力经 {@link Host} 收窄为具名方法：本类不持有 {@code MainController} / {@code MainViewRefs} /
 * FXML namespace，也不得在数据序上直写 —— 数据序唯一所有者是
 * {@link com.mhtextractor.app.WorkspaceModel}，分组/拆组的结构命令只经
 * {@code Host.createGroup(...)} / {@code Host.ungroupGroup(...)}（撤销记录由模型压入）。</p>
 *
 * <p>本类含 JavaFX，属 {@code gui} 层。测试源集的反射桥按字段名 {@code groupCommand} 与本类的
 * {@code toggleGroupExpanded} / {@code finishExpandAnim} 方法名反射（隐式契约）。</p>
 */
@Slf4j
public final class GroupCommandController {

    /**
     * 宿主能力面（C3a）：对 {@code MainController} 的全部能力需求收窄为具名方法。
     * 实现由 {@code MainController} 的匿名内部类提供（转发到其保留成员）。
     */
    public interface Host {
        // ---- 控件 ----
        javafx.scene.control.ListView<Object> fileListView();
        javafx.scene.layout.Pane dragOverlay();
        javafx.scene.control.TextField groupNameField();

        // ---- 模型投影（只读）+ 结构命令（唯一入口仍是 WorkspaceModel）----
        List<Object> dataOrder();                                      // = MainController.items
        int indexInDataOrder(Object item);                             // = items.indexOf(item)
        javafx.collections.ObservableList<Object> displayItems();      // = adapter.displayItems()
        GroupItem<PreviewItem> createGroup(List<PreviewItem> targets); // = ws.group(targets, 本地化默认名前缀 core.groupDefault)
        List<PreviewItem> ungroupGroup(GroupItem<PreviewItem> group);  // = ws.ungroup(group)

        // ---- 查询 / 选中读写 ----
        List<PreviewItem> allVisibleItems();                           // = allVisibleItems()
        Object currentSelection();                                     // = refs.fileListView().getSelectionModel().getSelectedItem()
        void selectRow(Object row);                                    // = refs.fileListView().getSelectionModel().select(row)
        void selectNearestVisible(int fromDisplayIndex);               // = selectNearestVisible(int)
        boolean isDragActive();                                        // = listInteraction.isDragActive()

        // ---- 派生刷新 / 收尾（实现留在 MainController）----
        void refreshAfterListChange();                                 // = refreshAfterListChange()
        void rebuildDisplay();                                         // = rebuildDisplay()
        void registerGroupNameRefresh(GroupItem<PreviewItem> group);   // = registerGroupNameRefresh(group)
        void setCheckedSilently(List<PreviewItem> targets, boolean value); // = listInteraction.setCheckedSilently(...)
    }

    /** 折叠/展开动画时长（拍板 200ms；缓动照搬 TitledPaneSkin 的 LINEAR） */
    private static final Duration EXPAND_ANIM_DURATION = Duration.millis(200);
    /** 成员行动画基准高度兜底（px）：ROW_PAD 7×2 + 单行文本 ≈ 34；动画开始时优先实测 */
    private static final double MEMBER_ROW_HEIGHT_FALLBACK = 34.0;

    private final Host host;

    public GroupCommandController(Host host) {
        this.host = host;
    }

    // ===== 折叠/展开动画状态（方案 B 抽屉式，快照平移手法与拖拽一致） =====
    /** 动画全局锁：动画期间列表项目不可交互（按钮可点但无效果），快速连点折叠箭头直接忽略 */
    private boolean listAnimating = false;
    /** 正在动画的组；null = 无动画 */
    private GroupItem<PreviewItem> animatingGroup = null;
    /** true = 展开动画；false = 折叠动画 */
    private boolean animExpanding = false;
    /** 动画 ParallelTransition（200ms ease-out；完成/快进后置 null） */
    private ParallelTransition collapseAnim = null;
    /** 动画中的快照视图（成员行 + 下方行；结束后从覆盖层移除） */
    private List<ImageView> collapseViews = null;
    /** 动画期间被隐藏的真实 cell（结束后恢复可见） */
    private List<PreviewListCell> collapseCells = null;
    /** 动画期间锁定的选中项：列表选择变化被回选至此项 */
    private Object animLockedSelection = null;
    /** 动画自身触发的显示序重建豁免标记（防快进兜底把动画自己的 rebuild 打断） */
    private boolean suppressAnimRebuild = false;

    /** 动画全局锁查询（C3a 起其余守卫改读本方法；ListInteractionController 经 Host 转发） */
    boolean isAnimating() {
        return listAnimating;
    }

    /** 动画锁定的选中项（可 null）；拖拽会话在动画期间保持原选中 */
    Object animLockedSelection() {
        return animLockedSelection;
    }

    /** 外部重建（换目录/模式切换等）时的快进兜底：动画自身触发的重建经 suppressAnimRebuild 豁免 */
    void fastForwardOnExternalRebuild() {
        if (listAnimating && !suppressAnimRebuild) {
            finishExpandAnim();
        }
    }

    /** 合并为组：有复选框勾选按勾选集合，否则按当前选中项（单个项目可建组 R9）；新组默认名"组N"（全局自增） */
    void onGroupClicked() {
        if (listAnimating) {
            return;
        }
        List<PreviewItem> targets = host.allVisibleItems().stream()
                .filter(i -> i.selected).toList();
        Object sel = host.currentSelection();
        if (targets.isEmpty()) {
            if (sel instanceof PreviewItem it && !it.removed) {
                targets = List.of(it);
            } else {
                return;
            }
        }
        // 结构（落点计算 / 成员摘除 / 致空组销毁 / 条目级参数清除 / 压 GROUP 撤销记录）全部由模型负责；
        // 组名前缀由宿主传入本地化文案 —— 模型不产出面向用户的文案。
        GroupItem<PreviewItem> group = host.createGroup(targets);
        if (group == null) {
            return;
        }
        host.registerGroupNameRefresh(group);
        // 拍板：合并为组后勾选集合复位，避免勾选残留导致下一批建组误合并
        host.setCheckedSilently(targets, false);
        host.selectRow(group);
        host.refreshAfterListChange();
        log.info(I18n.msg("log.maincontroller.56", group.getName(), targets.size()));
    }

    /** 拆分组（F6）：选中组条目时解散该组——成员按组内顺序回顶层原组位置，组名/组参数随组对象留在撤销记录中 */
    void onUngroupClicked() {
        if (listAnimating) {
            return;
        }
        Object sel = host.currentSelection();
        if (!(sel instanceof GroupItem<?> g)) {
            return;
        }
        GroupItem<PreviewItem> group = WorkspaceStructure.asPreviewGroup(g);
        if (host.indexInDataOrder(group) < 0) {
            return;
        }
        int displayIdx = host.displayItems().indexOf(group);
        // 拆分是整组操作，与展开/折叠无关。结构变更（成员按组内顺序回顶层原位置 + 组参数下发为
        // 成员条目级参数 + 压 UNGROUP 撤销记录）全部由模型负责，本类只做刷新与选中收尾。
        List<PreviewItem> members = host.ungroupGroup(group);
        host.refreshAfterListChange();
        // 选中的组已解散：跳转到原组位置的最近有效行（拆分后为原组第一个成员）
        host.selectNearestVisible(displayIdx);
        log.info(I18n.msg("log.maincontroller.57", group.getName(), members.size()));
    }

    /**
     * 组条目折叠/展开（方案 B 拍板）：抽屉式滑出/滑入——内容不裁切，成员行整体平移，
     * 下方行同步补位/让位（快照动画手法与拖拽一致）。200ms ease-out。
     * 动画期间列表项目不可交互（按钮可点但无效果）；快速连点/拖拽会话中直接忽略。
     */
    void toggleGroupExpanded(GroupItem<PreviewItem> g) {
        if (listAnimating || host.isDragActive()) {
            return;
        }
        if (g.isExpanded()) {
            collapseGroupAnimated(g);
        } else {
            expandGroupAnimated(g);
        }
    }

    /**
     * 折叠动画（抽屉收起）：成员行快照整体下滑滑出，下方行快照上滑补位。
     * 数据终态（expanded=false + 显示序移除成员）在动画开始时立即生效；
     * 外部重建触发快进兜底时只清理快照即可。
     */
    private void collapseGroupAnimated(GroupItem<PreviewItem> g) {
        Object sel = host.currentSelection();
        if (sel instanceof PreviewItem it && it.parentGroup == g) {
            host.selectRow(g);
        }
        listAnimating = true;
        animatingGroup = g;
        animExpanding = false;
        animLockedSelection = g;
        host.groupNameField().setDisable(true);

        // 采集快照（动画前：成员行与下方行均在原位置）；组行底部 = 成员收进抽屉的目标位置
        List<ImageView> memberViews = new ArrayList<>();
        List<ImageView> belowViews = new ArrayList<>();
        List<PreviewListCell> belowCells = new ArrayList<>();
        double groupBottom = -1;
        double memberBottom = -1;
        double totalHeight = 0;
        try {
            for (javafx.scene.Node n : host.fileListView().lookupAll(".list-cell")) {
                if (!(n instanceof PreviewListCell c) || c.getItem() == null
                        || (c.getItem() instanceof PreviewItem it && it.removed)) {
                    continue;
                }
                Bounds b = DragGeometry.cellBoundsInOverlay(c, host.dragOverlay());
                if (c.getItem() == g) {
                    groupBottom = b.getMaxY();
                } else if (c.getItem() instanceof PreviewItem it && it.parentGroup == g) {
                    Image snap = c.snapshot(DragGeometry.transparentSnapParams(), null);
                    if (snap == null) {
                        continue;   // 快照失败跳过该行，避免空白视图盖住列表
                    }
                    ImageView iv = new ImageView(snap);
                    iv.setLayoutX(b.getMinX());
                    iv.setLayoutY(b.getMinY());
                    host.dragOverlay().getChildren().add(iv);
                    memberViews.add(iv);
                    memberBottom = Math.max(memberBottom, b.getMaxY());
                    totalHeight += c.getHeight();
                } else if (memberBottom > 0 && b.getMinY() >= memberBottom - 1) {
                    // 成员块下方的行（原位置快照，动画中上滑补位）
                    Image snap = c.snapshot(DragGeometry.transparentSnapParams(), null);
                    if (snap == null) {
                        continue;
                    }
                    ImageView iv = new ImageView(snap);
                    iv.setLayoutX(b.getMinX());
                    iv.setLayoutY(b.getMinY());
                    host.dragOverlay().getChildren().add(iv);
                    belowViews.add(iv);
                    belowCells.add(c);
                }
            }
        } catch (Exception ex) {
            log.warn(I18n.msg("log.maincontroller.58", ex.getMessage()), ex);
            host.dragOverlay().getChildren().removeAll(memberViews);
            host.dragOverlay().getChildren().removeAll(belowViews);
            g.setExpanded(false);
            host.rebuildDisplay();
            finishExpandAnim();
            return;
        }

        // 数据终态：成员行移出显示序
        g.setExpanded(false);
        suppressAnimRebuild = true;
        try {
            host.rebuildDisplay();
        } finally {
            suppressAnimRebuild = false;
        }
        belowCells.forEach(c -> c.setVisible(false));

        double slide = totalHeight > 0 ? totalHeight : MEMBER_ROW_HEIGHT_FALLBACK;
        ParallelTransition pt = new ParallelTransition();
        for (ImageView iv : memberViews) {
            // 抽屉收起：每个成员滑到组行底部（位移 = 各自到组行底的距离，互不相同）
            double dist = Math.max(0, iv.getLayoutY() - groupBottom);
            TranslateTransition tt = new TranslateTransition(EXPAND_ANIM_DURATION, iv);
            tt.setByY(-dist);
            tt.setInterpolator(Interpolator.EASE_OUT);
            pt.getChildren().add(tt);
        }
        for (ImageView iv : belowViews) {
            TranslateTransition tt = new TranslateTransition(EXPAND_ANIM_DURATION, iv);
            tt.setByY(-slide);
            tt.setInterpolator(Interpolator.EASE_OUT);
            pt.getChildren().add(tt);
        }
        if (memberViews.isEmpty() && belowViews.isEmpty()) {
            // 无可见成员可动画：直接终态
            host.dragOverlay().getChildren().removeAll(memberViews);
            host.dragOverlay().getChildren().removeAll(belowViews);
            belowCells.forEach(c -> c.setVisible(true));
            finishExpandAnim();
            return;
        }
        collapseAnim = pt;
        collapseViews = memberViews;
        collapseViews.addAll(belowViews);
        collapseCells = belowCells;
        pt.setOnFinished(_ -> finishExpandAnim());
        pt.play();
    }

    /**
     * 展开动画（抽屉拉出）：显示序先重建到终态，成员行快照从上方滑入到位，
     * 下方行快照下滑让位。快进兜底只清理快照。
     */
    private void expandGroupAnimated(GroupItem<PreviewItem> g) {
        listAnimating = true;
        animatingGroup = g;
        animExpanding = true;
        animLockedSelection = host.currentSelection();
        host.groupNameField().setDisable(true);
        g.setExpanded(true);
        suppressAnimRebuild = true;
        try {
            host.rebuildDisplay();
        } finally {
            suppressAnimRebuild = false;
        }
        host.fileListView().layout();

        List<ImageView> memberViews = new ArrayList<>();
        List<ImageView> belowViews = new ArrayList<>();
        List<PreviewListCell> hiddenCells = new ArrayList<>();
        double groupBottom = -1;
        double memberBottom = -1;
        double totalHeight = 0;
        try {
            for (javafx.scene.Node n : host.fileListView().lookupAll(".list-cell")) {
                if (!(n instanceof PreviewListCell c) || c.getItem() == null
                        || (c.getItem() instanceof PreviewItem it && it.removed)) {
                    continue;
                }
                Bounds b = DragGeometry.cellBoundsInOverlay(c, host.dragOverlay());
                if (c.getItem() == g) {
                    groupBottom = b.getMaxY();
                } else if (c.getItem() instanceof PreviewItem it && it.parentGroup == g) {
                    Image snap = c.snapshot(DragGeometry.transparentSnapParams(), null);
                    if (snap == null) {
                        continue;   // 快照失败跳过该行，避免空白视图盖住列表
                    }
                    ImageView iv = new ImageView(snap);
                    iv.setLayoutX(b.getMinX());
                    iv.setLayoutY(b.getMinY());
                    host.dragOverlay().getChildren().add(iv);
                    memberViews.add(iv);
                    memberBottom = Math.max(memberBottom, b.getMaxY());
                    totalHeight += c.getHeight();
                    hiddenCells.add(c);
                } else if (memberBottom > 0 && b.getMinY() >= memberBottom - 1) {
                    Image snap = c.snapshot(DragGeometry.transparentSnapParams(), null);
                    if (snap == null) {
                        continue;
                    }
                    ImageView iv = new ImageView(snap);
                    iv.setLayoutX(b.getMinX());
                    iv.setLayoutY(b.getMinY());
                    host.dragOverlay().getChildren().add(iv);
                    belowViews.add(iv);
                    hiddenCells.add(c);
                }
            }
        } catch (Exception ex) {
            log.warn(I18n.msg("log.maincontroller.59", ex.getMessage()), ex);
            host.dragOverlay().getChildren().removeAll(memberViews);
            host.dragOverlay().getChildren().removeAll(belowViews);
            hiddenCells.forEach(c -> c.setVisible(true));
            finishExpandAnim();
            return;
        }

        hiddenCells.forEach(c -> c.setVisible(false));

        double slide = totalHeight > 0 ? totalHeight : MEMBER_ROW_HEIGHT_FALLBACK;
        // 抽屉拉出：每个成员快照从组行底部出发（初始位移 = 各自到组行底的距离），滑到各自位置
        for (ImageView iv : memberViews) {
            double dist = Math.max(0, iv.getLayoutY() - groupBottom);
            iv.setTranslateY(-dist);
        }
        for (ImageView iv : belowViews) {
            iv.setTranslateY(-slide);
        }
        ParallelTransition pt = new ParallelTransition();
        for (ImageView iv : memberViews) {
            TranslateTransition tt = new TranslateTransition(EXPAND_ANIM_DURATION, iv);
            tt.setByY(slide);
            tt.setInterpolator(Interpolator.EASE_OUT);
            pt.getChildren().add(tt);
        }
        for (ImageView iv : belowViews) {
            TranslateTransition tt = new TranslateTransition(EXPAND_ANIM_DURATION, iv);
            tt.setByY(slide);
            tt.setInterpolator(Interpolator.EASE_OUT);
            pt.getChildren().add(tt);
        }
        if (memberViews.isEmpty() && belowViews.isEmpty()) {
            host.dragOverlay().getChildren().removeAll(memberViews);
            host.dragOverlay().getChildren().removeAll(belowViews);
            hiddenCells.forEach(c -> c.setVisible(true));
            finishExpandAnim();
            return;
        }
        collapseAnim = pt;
        collapseViews = memberViews;
        collapseViews.addAll(belowViews);
        collapseCells = hiddenCells;
        pt.setOnFinished(_ -> finishExpandAnim());
        pt.play();
    }

    /** 折叠/展开动画结束（正常完成或外部重建快进）：清理快照、恢复 cell、解锁 */
    void finishExpandAnim() {
        if (collapseAnim != null) {
            collapseAnim.stop();
            collapseAnim = null;
        }
        if (collapseViews != null) {
            host.dragOverlay().getChildren().removeAll(collapseViews);
            collapseViews = null;
        }
        if (collapseCells != null) {
            collapseCells.forEach(c -> c.setVisible(true));
            collapseCells = null;
        }
        animatingGroup = null;
        animExpanding = false;
        animLockedSelection = null;
        listAnimating = false;
        host.groupNameField().setDisable(false);
        // 解锁后刷新单元格：动画开始时按 listAnimating=true 渲染的 checkbox 禁用状态需重渲染恢复
        host.fileListView().refresh();
        host.fileListView().requestLayout();
    }
}
