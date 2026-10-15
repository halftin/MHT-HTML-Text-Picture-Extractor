package com.mhtextractor.gui.controller;

import com.mhtextractor.app.SelectionRules;
import com.mhtextractor.app.Texts;
import com.mhtextractor.model.AppConfig;
import com.mhtextractor.gui.util.DragGeometry;
import com.mhtextractor.uicore.PreviewRow;
import com.mhtextractor.uicore.ExtractOutcomeStyles;
import com.mhtextractor.model.ChangeListener;
import com.mhtextractor.app.DropResolver;
import com.mhtextractor.model.ExtractOutcome;
import com.mhtextractor.uicore.GroupRow;
import com.mhtextractor.uicore.ParamScope;
import javafx.event.Event;
import lombok.extern.slf4j.Slf4j;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.Tooltip;
import javafx.scene.control.OverrunStyle;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import org.kordamp.ikonli.javafx.FontIcon;

@Slf4j
/*
  列表单元格（步骤 3 从宿主控制器的 430 行内部类外迁为顶层类）。

  <p><b>渲染与拖拽交互</b>：组行 / 项目行两种渲染（徽标、序号、文件名、状态），
  以及顶层项目拖拽排序（静态蓝线 + 滑动补位动画）。</p>

  <p><b>行为零变化</b>：逻辑逐字照搬，唯一改动是把原先隐式引用外部类实例的成员显式写成
  {@code actions.xxx} —— 顶层类不再持有隐式外部实例。</p>

  <p><b>为什么仍留在 {@code gui.controller} 包</b>：C2 起它不再依赖宿主控制器，只依赖窄行为接口
  {@link MainViewActions}（渲染查询 / 选择勾选交互 / 拖拽会话）；该接口的实现
  {@link ListInteractionController} 与本类同包。</p>
 */
class PreviewListCell extends ListCell<Object> implements CellIndicator {

    /** 行为面（C2：原先直接持有宿主控制器，现改为窄接口 {@link MainViewActions}） */
    private final MainViewActions actions;

    private final HBox box = new HBox(6);
    private final StackPane wrapper = new StackPane();
    private final CheckBox checkBox = new CheckBox();
    private final Label arrowLabel = new Label();
    private final Label badgeLabel = new Label();
    private final Label posLabel = new Label();
    private final Label nameLabel = new Label();
    private final Label sourceLabel = new Label();
    private final Label statusLabel = new Label();
    private final Region topLine = new Region();
    private final Region bottomLine = new Region();
    private final Region intoOverlay = new Region();
    private final FontIcon arrowIcon = new FontIcon();
    private final FontIcon statusIcon = new FontIcon();
    private PreviewRow boundItem;
    /**
     * 当前单元格与条目勾选状态的联动监听器（会话 18 · E1.3a）。
     *
     * <p>替换原 {@code bindBidirectional} 的"可解绑"能力：单元格复用时用
     * {@link PreviewRow#removeSelectedChangeListener(ChangeListener)} 解挂，
     * 再为新的条目建立新联动 —— 等价于原先的 {@code unbindBidirectional} → {@code bindBidirectional}。</p>
     */
    private ChangeListener selectedMirror;

    PreviewListCell(MainViewActions actions) {
        this.actions = actions;
        // 内边距与列头 HBox 一致（10px 水平），保证列对齐
        box.setPadding(ROW_PAD);
        checkBox.setMinWidth(CHECK_COL_WIDTH);
        checkBox.setPrefWidth(CHECK_COL_WIDTH);
        checkBox.setMaxWidth(CHECK_COL_WIDTH);
        checkBox.setAlignment(Pos.CENTER);
        checkBox.setFocusTraversable(false);
        checkBox.setStyle("-fx-padding: 0;");
        // 从复选框按下的拖拽手势在此消费，不冒泡到单元格，避免误触发排序拖拽（F1 2.4）
        checkBox.setOnDragDetected(Event::consume);
        arrowLabel.setMinWidth(16);
        arrowLabel.setPrefWidth(16);
        arrowLabel.setMaxWidth(16);
        arrowLabel.setAlignment(Pos.CENTER);
        arrowLabel.setStyle("-fx-padding: 0;");
        arrowLabel.setVisible(false);
        arrowLabel.setManaged(false);
        // 折叠箭头用 Ikonli 图标：主题字体可能缺少 ▾/▸ 字形导致空白
        arrowIcon.setIconSize(12);
        // 拍板 2026-09-22：箭头恒为白色——内联样式（优先级高于主题 CSS 的 -fx-icon-color），
        // 程序化 setIconColor 会被样式表覆盖（黑底上变深色不可见）
        arrowIcon.setStyle("-fx-icon-color: white;");
        arrowLabel.setGraphic(arrowIcon);
        // 状态列改用 Ikonli 图标：原 emoji（⏳/✅/⚠️/❌）在主题字体下会把变体选择符
        // U+FE0F 渲染成方框（⚠️ → 三角+方框），与折叠箭头弃用 ▾/▸ 同因。
        statusIcon.setIconSize(14);
        statusLabel.setGraphic(statusIcon);
        badgeLabel.setMinWidth(BADGE_COL_WIDTH);
        badgeLabel.setPrefWidth(BADGE_COL_WIDTH);
        badgeLabel.setMaxWidth(BADGE_COL_WIDTH);
        badgeLabel.setAlignment(Pos.CENTER);
        posLabel.setMinWidth(POS_COL_WIDTH);
        posLabel.setPrefWidth(POS_COL_WIDTH);
        posLabel.setMaxWidth(POS_COL_WIDTH);
        nameLabel.getStyleClass().add("preview-name");
        sourceLabel.getStyleClass().add("preview-source");
        sourceLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
        sourceLabel.setWrapText(false);
        listViewProperty().addListener((_, _, list) -> {
            if (list != null) PreviewColumns.bindCell(list, nameLabel, sourceLabel, statusLabel);
        });
        Region spacer = new Region();
        spacer.setManaged(false);
        box.getChildren().addAll(checkBox, arrowLabel, badgeLabel, posLabel, nameLabel, sourceLabel, spacer, statusLabel);

        // 插入指示线：覆盖在内容上下边缘的 2px 静态蓝线（StackPane 覆盖层，不占布局）。
        // 必须鼠标透明：否则 drop 事件目标为线本身，e.getY() 变为 0-2 导致落点分区误判（下缘落点被当成上缘）
        topLine.setMinHeight(2);
        topLine.setPrefHeight(2);
        topLine.setMaxHeight(2);
        topLine.setStyle(DROP_LINE_STYLE);
        topLine.setVisible(false);
        topLine.setMouseTransparent(true);
        StackPane.setAlignment(topLine, Pos.TOP_CENTER);
        bottomLine.setMinHeight(2);
        bottomLine.setPrefHeight(2);
        bottomLine.setMaxHeight(2);
        bottomLine.setStyle(DROP_LINE_STYLE);
        bottomLine.setVisible(false);
        bottomLine.setMouseTransparent(true);
        StackPane.setAlignment(bottomLine, Pos.BOTTOM_CENTER);

        // 组行整行高亮覆盖层（入组/合并语义）：鼠标透明，不拦截拖放事件
        intoOverlay.setStyle(INTO_OVERLAY_STYLE);
        intoOverlay.setVisible(false);
        intoOverlay.setMouseTransparent(true);

        wrapper.getChildren().addAll(box, topLine, bottomLine, intoOverlay);
        setupDnD();
    }

    @Override
    public void showInsertIndicator(boolean top) {
        intoOverlay.setVisible(false);
        topLine.setVisible(top);
        bottomLine.setVisible(!top);
    }

    @Override
    public void showIntoIndicator() {
        topLine.setVisible(false);
        bottomLine.setVisible(false);
        intoOverlay.setVisible(true);
    }

    @Override
    public void clearInsertIndicator() {
        topLine.setVisible(false);
        bottomLine.setVisible(false);
        intoOverlay.setVisible(false);
    }

    @SuppressWarnings("unchecked")   // 原在宿主控制器类级抑制；外迁后需在该方法上显式声明
    @Override
    protected void updateItem(Object item, boolean empty) {
        super.updateItem(item, empty);
        if (boundItem != null) {
            // 会话 18 · E1.3a：原 checkBox.selectedProperty().unbindBidirectional(boundItem.selected)
            // 现改为解挂自研监听器（等价：切断复选框与该条目的联动，避免单元格复用时串值）
            if (selectedMirror != null) {
                boundItem.removeSelectedChangeListener(selectedMirror);
                selectedMirror = null;
            }
            boundItem = null;
        }
        checkBox.setOnAction(null);
        arrowLabel.setOnMouseClicked(null);
        setOnMouseClicked(null);
        setOnMouseReleased(null);
        setOnMousePressed(null);
        clearInsertIndicator();
        sourceLabel.setText("");
        sourceLabel.setTooltip(null);
        // 折叠/展开动画期间、以及提取运行中列表项目不可交互：所有可见单元格复选框禁用
        checkBox.setDisable(actions.isListAnimating() || actions.isExtractionRunning());
        if (empty || item == null) {
            setGraphic(null);
            setTooltip(null);
            setStyle("");
            setVisible(false);
            setManaged(false);
            return;
        }
        if (item instanceof GroupRow<?> g) {
            renderGroupRow((GroupRow<PreviewRow>) g);
            // 折叠点击走 cell 级 pressed 判定（箭头槽位 cell x 22-46）：released 会被
            // checkbox 吞掉（间歇丢失），pressed 到达更可靠；判定由 actions.onCellPressed 统一处理
        } else if (item instanceof PreviewRow it) {
            if (it.removed) {
                setGraphic(null);
                setTooltip(null);
                setStyle("");
                setVisible(false);
                setManaged(false);
                return;
            }
            renderItemRow(it);
        }
        // Ctrl/Shift 多选手势（路线 B 拍板）：pressed 即处理（先于 released 的折叠判定，互不冲突）
        setOnMousePressed(e -> actions.onCellPressed(getItem(), e));
        // 双击 toggle 勾选（拍板）：clicked 的 clickCount==2 判定
        setOnMouseClicked(e -> actions.onCellDoubleClicked(getItem(), e));
    }

    /** 组条目行：组复选框（三态随成员联动）+ 折叠箭头 + 组名（项目数）；深蓝底、行高比文字稍高、不随参数变化 */
    private void renderGroupRow(GroupRow<PreviewRow> g) {
        setVisible(true);
        setManaged(true);
        box.setPadding(GROUP_ROW_PAD);
        // 行高锁定为紧凑高度（子组件如复选框默认高度会撑高整行）
        box.setMinHeight(GROUP_ROW_HEIGHT);
        box.setPrefHeight(GROUP_ROW_HEIGHT);
        box.setMaxHeight(GROUP_ROW_HEIGHT);
        // 组行复选框槽位缩窄（与成员行 40px 列错位是拍板接受的视觉取舍），内容紧跟顶格
        checkBox.setMinWidth(GROUP_CHECK_WIDTH);
        checkBox.setPrefWidth(GROUP_CHECK_WIDTH);
        checkBox.setMaxWidth(GROUP_CHECK_WIDTH);
        checkBox.setScaleX(0.75);
        checkBox.setScaleY(0.75);
        int total = actions.visibleMemberCount(g);
        int selected = (int) g.getMembers().stream()
                .filter(m -> !m.removed && m.selected).count();
        SelectionRules.TriState state = SelectionRules.computeState(selected, total);
        checkBox.setSelected(state == SelectionRules.TriState.ALL);
        checkBox.setIndeterminate(state == SelectionRules.TriState.SOME);
        checkBox.setOnAction(e -> {
            e.consume();
            actions.onGroupCheckToggled(g);
        });
        arrowIcon.setIconLiteral(g.isExpanded() ? "fas-caret-down" : "fas-caret-right");
        arrowLabel.setVisible(true);
        arrowLabel.setManaged(true);
        // 折叠点击由 cell 级 handler 判定（见 updateItem），此处不挂 arrowLabel 监听
        badgeLabel.setVisible(false);
        badgeLabel.setManaged(false);
        badgeLabel.setText("");
        badgeLabel.setStyle("");
        posLabel.setVisible(false);
        posLabel.setManaged(false);
        posLabel.setText("");
        nameLabel.setPadding(Insets.EMPTY);
        nameLabel.setText(g.getName() + Texts.get("list.itemCount", "count", total));
        // 组行字体加粗 + 白字（拍板）；-fx-text-background-color 不跨节点继承，须直接作用于 Label
        nameLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #ffffff;");
        statusLabel.setText("");
        statusLabel.setGraphic(null);
        // 选中态灰色高亮（拍板）：inline 深蓝会覆盖默认选中色，需按选中状态显式切换
        boolean isSelected = actions.isRowSelected(g);
        setStyle(isSelected ? GROUP_ROW_SELECTED_STYLE : GROUP_ROW_STYLE);
        setTooltip(null);
        setGraphic(wrapper);
    }

    /** 项目条目行（顶层与组内成员共用；组内成员文件名缩进显示） */
    private void renderItemRow(PreviewRow it) {
        setVisible(true);
        setManaged(true);
        boolean inGroup = it.parentGroup != null;
        // 组内成员（拍板）：行高略低、名称字体变小加粗；顶层项目维持现状
        box.setPadding(inGroup ? GROUP_MEMBER_PAD : ROW_PAD);
        box.setMinHeight(Region.USE_COMPUTED_SIZE);
        box.setPrefHeight(Region.USE_COMPUTED_SIZE);
        box.setMaxHeight(Region.USE_COMPUTED_SIZE);
        checkBox.setMinWidth(CHECK_COL_WIDTH);
        checkBox.setPrefWidth(CHECK_COL_WIDTH);
        checkBox.setMaxWidth(CHECK_COL_WIDTH);
        checkBox.setScaleX(1.0);
        checkBox.setScaleY(1.0);
        // 单元格可能刚渲染过组条目，先按项目勾选状态对齐再建立联动，避免初始同步串值
        checkBox.setSelected(it.selected);
        checkBox.setIndeterminate(false);
        // 会话 18 · E1.3a：原 checkBox.selectedProperty().bindBidirectional(it.selected)
        // 改为两条单向绑定，语义与双向绑定等价且更清晰：
        //   ① 用户点击复选框 → 写回条目勾选状态（经勾选唯一写入口 setChecked → setSelected 触发 onSelectionChanged）
        //   ② 条目勾选状态被程序修改 → 回写复选框显示
        // 单元格复用时靠上面的解挂切断旧联动（原先靠 unbindBidirectional）。
        checkBox.setOnAction(_ -> actions.setChecked(java.util.List.of(it), checkBox.isSelected()));
        selectedMirror = () -> checkBox.setSelected(it.selected);
        it.addSelectedChangeListener(selectedMirror);
        boundItem = it;
        arrowLabel.setVisible(false);
        arrowLabel.setManaged(false);
        AppConfig eff = actions.effectiveForObject(it);
        boolean showBadgeCol = actions.isTextMode() && eff != null && eff.isAddChapterNumber();
        badgeLabel.setVisible(showBadgeCol);
        // Individual chapter settings hide the badge without shifting the shared columns.
        badgeLabel.setManaged(PreviewColumns.hasBadgeColumn(getListView()));
        // 单元格可能刚渲染过组条目，恢复顺序栏可见（组行会隐藏该列，不复原则项目名左移挤占顺序栏）
        posLabel.setVisible(true);
        posLabel.setManaged(true);
        int pos = (int) actions.unitIndex(it) + 1;
        posLabel.setText(pos + ".");
        nameLabel.setPadding(inGroup ? new Insets(0, 0, 0, 16) : Insets.EMPTY);
        nameLabel.setText(it.name);
        sourceLabel.setText(it.sourceFolder == null ? "" : it.sourceFolder);
        sourceLabel.setTooltip(it.sourceFolder == null || it.sourceFolder.isEmpty()
                ? null : new Tooltip(it.sourceFolder));
        statusLabel.setText("");
        statusLabel.setGraphic(statusIcon);
        statusIcon.setIconLiteral(switch (it.status) {
            case WAITING -> "fas-hourglass-half";
            case READY -> "fas-check-circle";
            case EMPTY -> "fas-exclamation-triangle";
            case FAILED -> "fas-times-circle";
        });
        String badge = it.badge;
        if (badge == null) {
            badgeLabel.setText("");
            badgeLabel.setStyle("");
        } else {
            badgeLabel.setText(badge);
            badgeLabel.setStyle(BADGE_STYLE);
        }
        boolean isSelected = actions.isRowSelected(it);
        if (it.extractOutcome != ExtractOutcome.NONE) {
            // 提取结果色优先级最高（拍板）：覆盖解析失败黄与参数色；选中态用混合灰的显式变体色（组行同机制）
            setStyle(rowStyle(isSelected
                    ? ExtractOutcomeStyles.listPaintSelected(it.extractOutcome)
                    : ExtractOutcomeStyles.listPaint(it.extractOutcome)));
            setTooltip(it.extractDetail == null || it.extractDetail.isEmpty()
                    ? null : new Tooltip(it.extractDetail));
        } else if (it.status == PreviewRow.Status.FAILED) {
            setStyle(isSelected ? FAILED_SELECTED_ROW_STYLE : FAILED_ROW_STYLE);
            setTooltip(new Tooltip(it.errorMsg));
        } else {
            setStyle(itemParamStyle(it, isSelected));
            setTooltip(it.status == PreviewRow.Status.EMPTY
                    ? new Tooltip(Texts.get("preview.emptyContent")) : null);
        }
        // 深色底需白字（-fx-text-background-color 不跨节点继承，直接设 label 填充色）：
        // 全局参数深蓝底、提取失败/跳过/警告行（Windows 错误红 #E81123 及红绿分割行）
        boolean whiteText = ExtractOutcomeStyles.usesDarkBackdrop(it.extractOutcome)
                || (it.extractOutcome == ExtractOutcome.NONE
                        && it.status != PreviewRow.Status.FAILED
                        && actions.effectiveStatus(it) == ParamScope.Status.GLOBAL);
        nameLabel.setStyle((inGroup ? "-fx-font-size: 12px; -fx-font-weight: bold;" : "")
                + (whiteText ? " -fx-text-fill: #ffffff;" : ""));
        posLabel.setStyle(whiteText ? "-fx-text-fill: #ffffff;" : "");
        sourceLabel.setStyle(whiteText ? "-fx-text-fill: #ffffff;" : "");
        setGraphic(wrapper);
    }

    /** 8.5 条目背景色（拍板 2026-09-22 选中态切换，组行同机制）：无生效参数 = 现状色（选中用主题默认高亮）；
     *  全局参数 = 深蓝；组/条目级独立参数 = 浅蓝；选中时切换到混合灰的变体色。
     *  来源三态判定经 {@code actions.effectiveStatus} → {@code app.EffectiveParamsService}（唯一实现点，R8-①） */
    private String itemParamStyle(PreviewRow it, boolean selected) {
        String paint = switch (actions.effectiveStatus(it)) {
            case GLOBAL -> selected ? GLOBAL_PARAM_SELECTED_PAINT : GLOBAL_PARAM_PAINT;
            case GROUP -> selected ? GROUP_PARAM_SELECTED_PAINT : GROUP_PARAM_PAINT;
            case NONE -> null;
        };
        return paint == null ? CELL_PAD : rowStyle(paint);
    }

    private void setupDnD() {
        setOnDragDetected(e -> {
            // 阶段 7：项目（含组内成员）与组条目均可发起拖拽；已移除项已隐藏不可拖；
            // 折叠动画期间禁止拖拽；Ctrl/Shift 修饰键按下不启动拖拽（多选手势）
            if (actions.isListAnimating() || e.isControlDown() || e.isShiftDown()
                    || isEmpty() || getItem() == null
                    || (getItem() instanceof PreviewRow it && it.removed)) {
                return;
            }
            // 拖拽影像：带投影快照（临时挂投影后截图，随即撤掉）
            setEffect(new DropShadow(10, 0, 0, Color.rgb(0, 0, 0, 0.35)));
            Image snap = snapshot(DragGeometry.transparentSnapParams(), null);
            setEffect(null);
            Dragboard db = startDragAndDrop(TransferMode.MOVE);
            ClipboardContent cc = new ClipboardContent();
            cc.putString(String.valueOf(getIndex()));
            db.setContent(cc);
            if (snap != null) {
                db.setDragView(snap, snap.getWidth() / 2, 12);
            }
            actions.beginDrag(this);
            e.consume();
        });
        setOnDragOver(e -> {
            // 外部文件/文件夹拖入：本单元格不参与落点判定，仅放行事件给 ListView 级导入处理器。
            // 必须不 consume：本 handler 末尾无条件 e.consume() 会吞掉事件，列表级处理器永远收不到。
            // 不放 acceptTransferModes —— 落点位置无语义（导入一律追加到列表末尾）。
            if (e.getDragboard().hasFiles() && !actions.isDragActive()) {
                return;
            }
            // 不能用 getGestureSource() != this 做守卫：拖起后源单元格被 ListView 虚拟化复用
            // 去显示下一条目，光标悬停其上会被误判为"自身"而拒绝落点（向下拖拽必现）。
            // 本应用拖拽会话以 actions.draggedObject() 为准；自身落点由 resolve（dragged == target）拦截。
            if (e.getDragboard().hasString() && actions.draggedObject() != null) {
                Object t = getItem();
                if (t != null && !(t instanceof PreviewRow it && it.removed)) {
                    DropResolver.Zone zone = DropResolver.zoneFor(t, e.getY(), getHeight(), GROUP_ROW_EDGE_ZONE);
                    // 合法落点判定与落点解析共用同一规则（resolve 返回 null = 非法组合）
                    if (DropResolver.resolve(actions.dataOrder(), actions.draggedObject(), t, zone) != null) {
                        e.acceptTransferModes(TransferMode.MOVE);
                        if (zone == DropResolver.Zone.INTO) {
                            showIntoIndicator();
                        } else {
                            showInsertIndicator(zone == DropResolver.Zone.TOP);
                        }
                        actions.setLastDragOverCell(this);
                    }
                }
            }
            e.consume();
        });
        setOnDragExited(e -> {
            clearInsertIndicator();
            if (actions.lastDragOverCell() == this) {
                actions.setLastDragOverCell(null);
            }
            e.consume();
        });
        setOnDragDropped(e -> {
            // 外部文件/文件夹拖入：与 setOnDragOver 的放行分支对应——本单元格不消费 drop，
            // 事件冒泡到 ListView 级导入处理器（松手落在单元格上时若不放行，列表级处理器
            // 永远收不到，外部导入表现为"有高亮但拖不进来"）。
            if (e.getDragboard().hasFiles() && !actions.isDragActive()) {
                return;
            }
            Dragboard db = e.getDragboard();
            boolean ok = false;
            clearInsertIndicator();
            actions.setLastDragOverCell(null);
            try {
                if (db.hasString() && actions.draggedObject() != null) {
                    handleDrop(e);
                    ok = actions.isDropHandled();
                }
            } catch (Exception ex) {
                log.warn(Texts.msg("log.previewlistcell.01", ex.getMessage()), ex);
            }
            e.setDropCompleted(ok);
            e.consume();
        });
        setOnDragDone(e -> {
            if (actions.lastDragOverCell() != null) {
                actions.lastDragOverCell().clearInsertIndicator();
                actions.setLastDragOverCell(null);
            }
            // 未发生 drop 的拖拽结束（Esc / 拖出列表）→ 回到原位。
            // 守卫不检查 actions.draggedObject()：actions.cancelDrag() 只做"重建显示序 + 复位状态"（数据序未动），
            // actions.draggedObject() 为 null 时同样能恢复显示（防"条目消失 + 交互锁死"的卡死形态）
            if (actions.isDragActive() && !actions.isDropHandled()) {
                actions.cancelDrag();
            }
            e.consume();
        });
    }

    /**
     * 拖放执行：由 DnD 事件驱动（cell 非空时附带释放动画）。
     * 解析/执行/撤销记录等核心逻辑见 {@link MainViewActions#performDrop}。
     */
    private void handleDrop(javafx.scene.input.DragEvent e) {
        Object t = getItem();
        if (t == null || (t instanceof PreviewRow it && it.removed)) {
            return;
        }
        DropResolver.Zone zone = DropResolver.zoneFor(t, e.getY(), getHeight(), GROUP_ROW_EDGE_ZONE);
        if (actions.performDrop(actions.draggedObject(), t, zone, this)) {
            actions.markDropHandled();
        }
    }


    // ===== 单元格表现层常量（步骤 3 从宿主控制器迁入：原先只被本类使用） =====
    private static final String CELL_PAD = "-fx-padding: 0;";
    private static final double BADGE_COL_WIDTH = 40;
    private static final String BADGE_STYLE =
            "-fx-background-color: -color-accent-emphasis; -fx-text-fill: white;"
                    + " -fx-padding: 1 4; -fx-background-radius: 9;";
    private static final double CHECK_COL_WIDTH = 40;
    private static final String DROP_LINE_STYLE = "-fx-background-color: #3b82f6;";
    private static final String GLOBAL_PARAM_PAINT = "#1e4d8f";
    private static final String GLOBAL_PARAM_SELECTED_PAINT = "#5e7aa2";
    private static final double GROUP_CHECK_WIDTH = 20.0;
    private static final Insets GROUP_MEMBER_PAD = new Insets(2, 10, 2, 10);
    private static final String GROUP_PARAM_PAINT = "#d6eaf8";
    private static final String GROUP_PARAM_SELECTED_PAINT = "#ccd8e1";
    private static final double GROUP_ROW_HEIGHT = 20.0;
    private static final Insets GROUP_ROW_PAD = new Insets(1, 2, 1, 10);
    private static final String GROUP_ROW_SELECTED_STYLE =
            CELL_PAD + " -fx-background-color: #444444; -fx-text-background-color: #ffffff;";
    private static final String GROUP_ROW_STYLE =
            CELL_PAD + " -fx-background-color: #000000; -fx-text-background-color: #ffffff;";
    private static final String INTO_OVERLAY_STYLE =
            "-fx-background-color: rgba(245, 158, 11, 0.18); -fx-border-color: #f59e0b; -fx-border-width: 2;";
    private static final double POS_COL_WIDTH = 40;
    static final double GROUP_ROW_EDGE_ZONE = 0.25;
    private static final String FAILED_PAINT = "#fff9ae";
    private static final String FAILED_SELECTED_PAINT = "#e5e1b4";
    private static final String FAILED_ROW_STYLE = CELL_PAD + " -fx-background-color: " + FAILED_PAINT
            + "; -fx-text-background-color: #856404;";
    private static final String FAILED_SELECTED_ROW_STYLE = CELL_PAD + " -fx-background-color: "
            + FAILED_SELECTED_PAINT + "; -fx-text-background-color: #856404;";

    // ===== 迁移常量（步骤 3：原先只被本类使用的表现层常量/样式组装） =====
    private static final Insets ROW_PAD = new Insets(7, 10, 7, 10);

    /** 行背景样式组装（步骤 3 从宿主控制器迁入，仅本类使用） */
    private static String rowStyle(String paint) {
        return CELL_PAD + " -fx-background-color: " + paint + ";";
    }
}
