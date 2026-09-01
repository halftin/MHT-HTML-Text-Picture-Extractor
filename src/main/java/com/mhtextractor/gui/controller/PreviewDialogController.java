package com.mhtextractor.gui.controller;

import com.mhtextractor.config.AppConfig;
import com.mhtextractor.core.MimeParser;
import com.mhtextractor.extractor.ImageExtractor;
import com.mhtextractor.extractor.TextExtractor;
import com.mhtextractor.output.FileWriter;
import com.mhtextractor.output.ImageWriter;
import com.mhtextractor.processor.BatchProcessor;
import com.mhtextractor.processor.ImageBatchProcessor;
import com.mhtextractor.processor.PerFileOverrides;
import com.mhtextractor.util.FileUtils;
import com.mhtextractor.util.SanitizeUtils;
import javafx.animation.ParallelTransition;
import javafx.animation.TranslateTransition;
import javafx.application.Platform;
import org.glavo.webp.WebPImage;
import org.glavo.webp.WebPImageLoadOptions;
import org.glavo.webp.javafx.WebPFXImage;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.BoundingBox;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Point2D;
import javafx.geometry.Pos;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.SnapshotParameters;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.ScrollBar;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.effect.DropShadow;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.paint.Color;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 提取前预览与调整窗口控制器
 *
 * <p>模态入口 {@link #openAndWait(Window, AppConfig)}（真实用户流程）；
 * 测试/agent 直驱入口 {@link #showDirect(Window, AppConfig)}（非模态，避免阻塞调用线程）。</p>
 */
@Slf4j
public class PreviewDialogController {

    /** 超大文件完整解析确认阈值（字节） */
    private static final long LARGE_FILE_THRESHOLD = 50L * 1024 * 1024;

    /** 单元格内边距清零（列头与单元格列对齐由各自 HBox 的内边距控制） */
    private static final String CELL_PAD = "-fx-padding: 0;";
    private static final String FAILED_STYLE =
            CELL_PAD + " -fx-background-color: #f8d7da; -fx-text-background-color: #721c24;";
    private static final String BADGE_STYLE =
            "-fx-background-color: -color-accent-emphasis; -fx-text-fill: white;"
                    + " -fx-padding: 1 4; -fx-background-radius: 9;";
    /** 插入指示线颜色（拖拽排序反馈） */
    private static final String DROP_LINE_STYLE = "-fx-background-color: #3b82f6;";

    /** 徽标列/位置序号列的固定槽位宽度 */
    private static final double BADGE_COL_WIDTH = 40;
    private static final double POS_COL_WIDTH = 40;

    /** 条目预解析状态 */
    enum Status { WAITING, READY, EMPTY, FAILED }

    /** 缩略图条目：解码后的 JavaFX 图像 + 不含 f%02d_ 前缀的文件名（重排时按新位置重算前缀） */
    private record ThumbEntry(Image image, String coreName) {}

    /** 单个 MHT 文件的预览状态 */
    class PreviewItem {
        final String path;
        final String name;
        final LocalDateTime modifiedTime;
        final ObjectProperty<Status> status = new SimpleObjectProperty<>(Status.WAITING);
        final StringProperty errorMsg = new SimpleStringProperty("");
        final BooleanProperty chapterEnabled = new SimpleBooleanProperty(true);
        final StringProperty badge = new SimpleStringProperty(null);
        volatile String cachedText;
        volatile String cachedTitle;
        volatile List<ImageExtractor.ImageData> cachedImages;
        volatile List<ThumbEntry> cachedThumbs;

        PreviewItem(String path, String name, LocalDateTime modifiedTime) {
            this.path = path;
            this.name = name;
            this.modifiedTime = modifiedTime;
        }
    }

    @FXML private Label fileCountLabel;
    @FXML private Label modeLabel;
    @FXML private Label badgeHeaderLabel;
    @FXML ListView<PreviewItem> fileListView;
    @FXML private Pane dragOverlay;
    @FXML private Label outputModeLabel;
    @FXML private Label outputNameLabel;
    @FXML private Label titleInfoLabel;
    @FXML private Label outputResultLabel;
    @FXML CheckBox adjustCheck;
    @FXML private TextArea textPreviewArea;
    @FXML private VBox imagePreviewBox;
    @FXML private Label imageHintLabel;
    @FXML Button previewEffectButton;
    @FXML private Label renameNoteLabel;
    @FXML private ScrollPane imageScrollPane;
    @FXML private FlowPane thumbnailPane;
    @FXML private ProgressBar progressBar;
    @FXML private Label statusLabel;
    @FXML Button cancelButton;
    @FXML private Button extractButton;

    private final ObservableList<PreviewItem> items = FXCollections.observableArrayList();
    private AppConfig snapshot;
    private Stage stage;
    private volatile boolean closed = false;
    private boolean confirmed = false;
    private int requestToken = 0;
    private boolean imageLoadActive = false;
    private PreviewItem loadingItem = null;
    private boolean updatingAdjustCheck = false;
    private PreviewItem lastSelected = null;
    private CellIndicator lastDragOverCell = null;

    // ===== 拖拽排序状态 =====
    private PreviewItem draggedItem = null;
    private int draggedIndex = -1;
    private double dragGapTopY = 0;
    private boolean dragActive = false;
    private boolean dropHandled = false;
    private boolean dragPositionStale = false;
    private ParallelTransition liftAnim = null;
    private List<PreviewListCell> liftCells = null;
    private List<ImageView> liftViews = null;

    // ==================== 窗口入口 ====================

    /**
     * 模态打开预览窗口（真实用户流程），窗口关闭后返回控制器
     */
    public static PreviewDialogController openAndWait(Window owner, AppConfig snapshot) throws IOException {
        PreviewDialogController ctrl = create(snapshot);
        ctrl.stage.initOwner(owner);
        ctrl.stage.initModality(Modality.APPLICATION_MODAL);
        ctrl.stage.showAndWait();
        return ctrl;
    }

    /**
     * 非模态直驱入口（agent 演练 / 自动化测试用，避免阻塞调用线程）
     */
    public static PreviewDialogController showDirect(Window owner, AppConfig snapshot) throws IOException {
        PreviewDialogController ctrl = create(snapshot);
        if (owner != null) {
            ctrl.stage.initOwner(owner);
        }
        ctrl.stage.show();
        return ctrl;
    }

    private static PreviewDialogController create(AppConfig snapshot) throws IOException {
        FXMLLoader loader = new FXMLLoader(
                PreviewDialogController.class.getResource("/com/mhtextractor/gui/view/PreviewDialog.fxml"));
        Parent root = loader.load();
        PreviewDialogController ctrl = loader.getController();

        Stage stage = new Stage();
        stage.setTitle("提取前预览与调整");
        stage.setScene(new Scene(root));
        stage.setWidth(1000);
        stage.setHeight(650);
        stage.setMinWidth(800);
        stage.setMinHeight(500);
        stage.setOnCloseRequest(e -> {
            ctrl.closed = true;
            ctrl.cleanupDragState();
        });

        ctrl.stage = stage;
        ctrl.configure(snapshot);
        return ctrl;
    }

    // ==================== 初始化 ====================

    @FXML
    private void initialize() {
        fileListView.setCellFactory(lv -> createPreviewCell());

        fileListView.getSelectionModel().selectedItemProperty()
                .addListener((obs, old, newVal) -> onItemSelected(newVal));

        adjustCheck.selectedProperty().addListener((obs, old, newVal) -> {
            if (updatingAdjustCheck || snapshot == null) {
                return;
            }
            PreviewItem sel = fileListView.getSelectionModel().getSelectedItem();
            if (sel == null) {
                return;
            }
            if (isTextMode()) {
                sel.chapterEnabled.set(newVal);
                recomputeBadges();
            }
        });

        // 拖拽动画期间若列表发生滚动（滚轮/自动滚动），标记位置失效并结束提起动画
        fileListView.skinProperty().addListener((obs, oldSkin, newSkin) -> {
            if (newSkin == null) {
                return;
            }
            for (javafx.scene.Node n : fileListView.lookupAll(".scroll-bar")) {
                if (n instanceof ScrollBar sb && sb.getOrientation() == Orientation.VERTICAL) {
                    sb.valueProperty().addListener((o, a, b) -> {
                        if (dragActive && Math.abs(b.doubleValue() - a.doubleValue()) > 0.1) {
                            dragPositionStale = true;
                            finishLift();
                        }
                    });
                }
            }
        });
    }

    private void configure(AppConfig snapshot) {
        this.snapshot = snapshot;
        List<File> files = FileUtils.findMhtFiles(snapshot.getInputFolder());
        for (File f : files) {
            BatchProcessor.MhtFileInfo info = BatchProcessor.toMhtFileInfo(f);
            items.add(new PreviewItem(info.getPath(), info.getName(), info.getModifiedTime()));
        }
        fileListView.setItems(items);
        fileCountLabel.setText("共 " + items.size() + " 个文件");
        modeLabel.setText("模式：" + (isTextMode() ? "文本" : "图片"));

        // 徽标列头：仅文本模式 + 全局章节开启时显示
        badgeHeaderLabel.setTooltip(new Tooltip(
                "勾选\"添加章节序号\"的文件的最终章节号（阿拉伯数字），随勾选与排序实时变化"));
        boolean showBadgeCol = isTextMode() && snapshot.isAddChapterNumber();
        badgeHeaderLabel.setVisible(showBadgeCol);
        badgeHeaderLabel.setManaged(showBadgeCol);

        textPreviewArea.setVisible(isTextMode());
        textPreviewArea.setManaged(isTextMode());
        imagePreviewBox.setVisible(!isTextMode());
        imagePreviewBox.setManaged(!isTextMode());

        outputModeLabel.setText(isTextMode()
                ? (snapshot.isSeparateFiles() ? "输出模式：单独输出模式" : "输出模式：合并输出模式")
                : (snapshot.isSingleOutputMode() ? "输出模式：合并输出模式" : "输出模式：单独输出模式"));

        outputResultLabel.setText("输出结果：" + outputResultText());

        updateRenameNote();
        fileListView.getSelectionModel().selectFirst();
        updateProgressStatus();
        startPreParse();
    }

    // ==================== 列表渲染与拖拽排序 ====================

    /** 可显示插入指示线的单元格（拖拽排序反馈） */
    private interface CellIndicator {
        void showInsertIndicator(boolean top);

        void clearInsertIndicator();
    }

    private ListCell<PreviewItem> createPreviewCell() {
        return new PreviewListCell();
    }

    /**
     * 预览文件列表单元格：徽标/顺序/文件名/状态 + 拖拽排序（静态蓝线 + 滑动补位动画）
     */
    private class PreviewListCell extends ListCell<PreviewItem> implements CellIndicator {
        private final HBox box = new HBox(6);
        private final StackPane wrapper = new StackPane();
        private final Label badgeLabel = new Label();
        private final Label posLabel = new Label();
        private final Label nameLabel = new Label();
        private final Label statusLabel = new Label();
        private final Region spacer = new Region();
        private final Region topLine = new Region();
        private final Region bottomLine = new Region();

        PreviewListCell() {
            // 内边距与列头 HBox 一致（10px 水平），保证列对齐
            box.setPadding(new Insets(7, 10, 7, 10));
            badgeLabel.setMinWidth(BADGE_COL_WIDTH);
            badgeLabel.setPrefWidth(BADGE_COL_WIDTH);
            badgeLabel.setMaxWidth(BADGE_COL_WIDTH);
            badgeLabel.setAlignment(Pos.CENTER);
            posLabel.setMinWidth(POS_COL_WIDTH);
            posLabel.setPrefWidth(POS_COL_WIDTH);
            HBox.setHgrow(spacer, Priority.ALWAYS);
            box.getChildren().addAll(badgeLabel, posLabel, nameLabel, spacer, statusLabel);

            // 插入指示线：覆盖在内容上下边缘的 2px 静态蓝线（StackPane 覆盖层，不占布局）
            topLine.setMinHeight(2);
            topLine.setPrefHeight(2);
            topLine.setMaxHeight(2);
            topLine.setStyle(DROP_LINE_STYLE);
            topLine.setVisible(false);
            StackPane.setAlignment(topLine, Pos.TOP_CENTER);
            bottomLine.setMinHeight(2);
            bottomLine.setPrefHeight(2);
            bottomLine.setMaxHeight(2);
            bottomLine.setStyle(DROP_LINE_STYLE);
            bottomLine.setVisible(false);
            StackPane.setAlignment(bottomLine, Pos.BOTTOM_CENTER);

            wrapper.getChildren().addAll(box, topLine, bottomLine);
            setupDnD();
        }

        @Override
        public void showInsertIndicator(boolean top) {
            topLine.setVisible(top);
            bottomLine.setVisible(!top);
        }

        @Override
        public void clearInsertIndicator() {
            topLine.setVisible(false);
            bottomLine.setVisible(false);
        }

        @Override
        protected void updateItem(PreviewItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                clearInsertIndicator();
                setGraphic(null);
                setTooltip(null);
                setStyle("");
                return;
            }
            boolean showBadgeCol = isTextMode() && snapshot.isAddChapterNumber();
            badgeLabel.setVisible(showBadgeCol);
            badgeLabel.setManaged(showBadgeCol);
            int pos = items.indexOf(item) + 1;
            posLabel.setText(pos + ".");
            nameLabel.setText(item.name);
            statusLabel.setText(switch (item.status.get()) {
                case WAITING -> "⏳";
                case READY -> "✅";
                case EMPTY -> "⚠️";
                case FAILED -> "❌";
            });
            String badge = item.badge.get();
            if (badge == null) {
                badgeLabel.setText("");
                badgeLabel.setStyle("");
            } else {
                badgeLabel.setText(badge);
                badgeLabel.setStyle(BADGE_STYLE);
            }
            if (item.status.get() == Status.FAILED) {
                setStyle(FAILED_STYLE);
                setTooltip(new Tooltip(item.errorMsg.get()));
            } else {
                setStyle(CELL_PAD);
                setTooltip(item.status.get() == Status.EMPTY
                        ? new Tooltip("内容为空，正式提取时将被跳过") : null);
            }
            setGraphic(wrapper);
        }

        private void setupDnD() {
            setOnDragDetected(e -> {
                if (isEmpty() || getItem() == null) {
                    return;
                }
                // 拖拽影像：带投影快照（临时挂投影后截图，随即撤掉）
                setEffect(new DropShadow(10, 0, 0, Color.rgb(0, 0, 0, 0.35)));
                Image snap = snapshot(transparentSnapParams(), null);
                setEffect(null);
                Dragboard db = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent cc = new ClipboardContent();
                cc.putString(String.valueOf(getIndex()));
                db.setContent(cc);
                if (snap != null) {
                    db.setDragView(snap, snap.getWidth() / 2, 12);
                }
                beginDrag(this);
                e.consume();
            });
            setOnDragOver(e -> {
                if (e.getGestureSource() != this && e.getDragboard().hasString()) {
                    e.acceptTransferModes(TransferMode.MOVE);
                    boolean lowerHalf = e.getY() > getHeight() / 2;
                    showInsertIndicator(!lowerHalf);
                    lastDragOverCell = this;
                }
                e.consume();
            });
            setOnDragExited(e -> {
                clearInsertIndicator();
                if (lastDragOverCell == this) {
                    lastDragOverCell = null;
                }
                e.consume();
            });
            setOnDragDropped(e -> {
                Dragboard db = e.getDragboard();
                boolean ok = false;
                clearInsertIndicator();
                lastDragOverCell = null;
                try {
                    if (db.hasString() && draggedItem != null) {
                        boolean lowerHalf = e.getY() > getHeight() / 2;
                        int to = getIndex();
                        int insertAt = Math.min(to + (lowerHalf ? 1 : 0), items.size());
                        // 立即结束提起动画（若仍在进行），快照按当前实际位置重新采集
                        finishLift();
                        double gapTopY = cellBoundsInOverlay(this).getMinY()
                                + (lowerHalf ? getHeight() : 0);
                        dropHandled = true;
                        animateReleaseInsert(insertAt, gapTopY, draggedItem);
                        ok = true;
                    }
                } catch (Exception ex) {
                    log.warn("拖拽排序失败：{}", ex.getMessage(), ex);
                }
                e.setDropCompleted(ok);
                e.consume();
            });
            setOnDragDone(e -> {
                if (lastDragOverCell != null) {
                    lastDragOverCell.clearInsertIndicator();
                    lastDragOverCell = null;
                }
                // 未发生 drop 的拖拽结束（Esc / 拖出列表）→ 回到原位
                if (dragActive && !dropHandled && draggedItem != null) {
                    cancelDrag();
                }
                e.consume();
            });
        }
    }

    // ==================== 拖拽排序动画 ====================

    private static SnapshotParameters transparentSnapParams() {
        SnapshotParameters sp = new SnapshotParameters();
        sp.setFill(Color.TRANSPARENT);
        return sp;
    }

    /** 单元格在拖拽覆盖层坐标系中的位置 */
    private Bounds cellBoundsInOverlay(javafx.scene.Node cell) {
        Bounds sceneBounds = cell.localToScene(cell.getBoundsInLocal());
        Point2D origin = dragOverlay.localToScene(0, 0);
        return new BoundingBox(
                sceneBounds.getMinX() - origin.getX(),
                sceneBounds.getMinY() - origin.getY(),
                sceneBounds.getWidth(),
                sceneBounds.getHeight());
    }

    private PreviewListCell findCell(PreviewItem item) {
        for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
            if (n instanceof PreviewListCell c && c.getItem() == item) {
                return c;
            }
        }
        return null;
    }

    /** 提起：被拖条目瞬间移除，下方条目快照上滑 0.2s 补位 */
    private void beginDrag(PreviewListCell sourceCell) {
        draggedItem = sourceCell.getItem();
        draggedIndex = items.indexOf(draggedItem);
        dragPositionStale = false;
        dragActive = true;
        dragGapTopY = cellBoundsInOverlay(sourceCell).getMinY();
        double rowHeight = sourceCell.getHeight();

        List<PreviewListCell> cells = new ArrayList<>();
        List<ImageView> views = new ArrayList<>();
        try {
            for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
                if (n instanceof PreviewListCell c && c != sourceCell && c.getItem() != null) {
                    Bounds b = cellBoundsInOverlay(c);
                    if (b.getMinY() >= dragGapTopY - 1) {
                        cells.add(c);
                        ImageView iv = new ImageView(c.snapshot(transparentSnapParams(), null));
                        iv.setLayoutX(b.getMinX());
                        iv.setLayoutY(b.getMinY());
                        dragOverlay.getChildren().add(iv);
                        views.add(iv);
                    }
                }
            }
        } catch (Exception ex) {
            // 快照失败兜底：跳过动画，瞬时重排
            log.warn("拖拽快照失败，退化为瞬时重排：{}", ex.getMessage(), ex);
            dragOverlay.getChildren().removeAll(views);
            return;
        }

        items.remove(draggedItem);
        cells.forEach(c -> c.setVisible(false));

        liftCells = cells;
        liftViews = views;
        liftAnim = new ParallelTransition();
        for (ImageView iv : views) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), iv);
            tt.setByY(-rowHeight);
            liftAnim.getChildren().add(tt);
        }
        liftAnim.setOnFinished(e -> finishLift());
        liftAnim.play();
    }

    /** 结束提起动画：清除快照、恢复单元格显示 */
    private void finishLift() {
        if (liftAnim != null) {
            liftAnim.stop();
            liftAnim = null;
        }
        if (liftViews != null) {
            dragOverlay.getChildren().removeAll(liftViews);
            liftViews = null;
        }
        if (liftCells != null) {
            liftCells.forEach(c -> c.setVisible(true));
            liftCells = null;
        }
    }

    /**
     * 松手/取消共用：条目瞬间出现在目标位置（静态快照置于最上层），
     * 下方条目快照从其底下下滑 0.2s 让位
     */
    private void animateReleaseInsert(int insertAt, double gapTopY, PreviewItem item) {
        double rowHeight = 0;
        List<PreviewListCell> cells = new ArrayList<>();
        List<ImageView> views = new ArrayList<>();
        try {
            for (javafx.scene.Node n : fileListView.lookupAll(".list-cell")) {
                if (n instanceof PreviewListCell c && c.getItem() != null) {
                    Bounds b = cellBoundsInOverlay(c);
                    if (b.getMinY() >= gapTopY - 1) {
                        cells.add(c);
                        ImageView iv = new ImageView(c.snapshot(transparentSnapParams(), null));
                        iv.setLayoutX(b.getMinX());
                        iv.setLayoutY(b.getMinY());
                        dragOverlay.getChildren().add(iv);
                        views.add(iv);
                        rowHeight = c.getHeight();
                    }
                }
            }
        } catch (Exception ex) {
            log.warn("拖拽快照失败，退化为瞬时重排：{}", ex.getMessage(), ex);
            dragOverlay.getChildren().removeAll(views);
            items.add(insertAt, item);
            finishDragReset();
            return;
        }

        if (rowHeight <= 0) {
            rowHeight = 39;
        }

        items.add(insertAt, item);
        fileListView.getSelectionModel().select(item);
        // 同步完成单元格绑定，保证后续快照/隐藏准确
        fileListView.layout();
        cells.forEach(c -> c.setVisible(false));
        PreviewListCell itemCell = findCell(item);
        if (itemCell != null) {
            // 条目行快照最后加入覆盖层（位于最上层、静态不动），下方快照下滑时从其底下钻过
            try {
                Bounds ib = cellBoundsInOverlay(itemCell);
                ImageView itemView = new ImageView(itemCell.snapshot(transparentSnapParams(), null));
                itemView.setLayoutX(ib.getMinX());
                itemView.setLayoutY(ib.getMinY());
                dragOverlay.getChildren().add(itemView);
            } catch (Exception ex) {
                log.warn("条目行快照失败，该行在动画结束后显示：{}", ex.getMessage(), ex);
            }
            itemCell.setVisible(false);
        }

        ParallelTransition pt = new ParallelTransition();
        for (ImageView iv : views) {
            TranslateTransition tt = new TranslateTransition(Duration.millis(200), iv);
            tt.setByY(rowHeight);
            pt.getChildren().add(tt);
        }
        pt.setOnFinished(e -> {
            dragOverlay.getChildren().clear();
            cells.forEach(c -> c.setVisible(true));
            if (itemCell != null) {
                itemCell.setVisible(true);
            }
            finishDragReset();
        });
        pt.play();
    }

    /** 拖拽全流程结束：复位状态并重算徽标/信息区 */
    private void finishDragReset() {
        draggedItem = null;
        dragActive = false;
        dropHandled = false;
        onOrderChanged();
    }

    /** 取消拖拽：条目回到原索引，动画与松手一致；列表已滚动则瞬时恢复 */
    private void cancelDrag() {
        finishLift();
        if (dragPositionStale) {
            items.add(Math.min(draggedIndex, items.size()), draggedItem);
            finishDragReset();
            return;
        }
        animateReleaseInsert(Math.min(draggedIndex, items.size()), dragGapTopY, draggedItem);
    }

    /** 窗口关闭时清理拖拽动画残留 */
    private void cleanupDragState() {
        finishLift();
        dragOverlay.getChildren().clear();
        draggedItem = null;
        dragActive = false;
        dropHandled = false;
    }

    private void onOrderChanged() {
        recomputeBadges();
        PreviewItem sel = fileListView.getSelectionModel().getSelectedItem();
        updateInfoArea(sel);
        if (!isTextMode() && sel != null && sel.cachedThumbs != null) {
            renderThumbnails(sel);
        }
    }

    // ==================== 选择与联动 ====================

    private void onItemSelected(PreviewItem item) {
        requestToken++;
        imageLoadActive = false;
        loadingItem = null;
        if (lastSelected != null) {
            // 仅当前文件的图片数据驻留内存
            lastSelected.cachedImages = null;
            lastSelected.cachedThumbs = null;
        }
        lastSelected = item;
        updateAdjustCheck(item);
        updateInfoArea(item);
        updateContentArea(item);
    }

    private void recomputeBadges() {
        int n = 1;
        for (PreviewItem it : items) {
            if (isTextMode() && snapshot.isAddChapterNumber()
                    && it.status.get() == Status.READY && it.chapterEnabled.get()) {
                it.badge.set(String.valueOf(n++));
            } else {
                it.badge.set(null);
            }
        }
        fileListView.refresh();
    }

    private void updateAdjustCheck(PreviewItem item) {
        // 第三行：文本模式 = 章节复选框（逐文件调整）；图片模式 = 输出结果展示（全局打包配置）
        boolean checkVisible = isTextMode() && item != null && snapshot.isAddChapterNumber();
        adjustCheck.setVisible(checkVisible);
        adjustCheck.setManaged(checkVisible);
        if (checkVisible) {
            adjustCheck.setText("添加章节序号");
            updatingAdjustCheck = true;
            adjustCheck.setSelected(item.chapterEnabled.get());
            updatingAdjustCheck = false;
        }
        outputResultLabel.setVisible(!isTextMode());
        outputResultLabel.setManaged(!isTextMode());
    }

    /** 图片模式输出结果说明：文件夹 / ZIP / CBZ / ZIP + CBZ（打包由全局配置决定） */
    private String outputResultText() {
        boolean zip = snapshot.isEnableZipPackaging();
        boolean cbz = snapshot.isEnableCbzPackaging();
        if (zip && cbz) {
            return "ZIP + CBZ";
        }
        if (zip) {
            return "ZIP";
        }
        if (cbz) {
            return "CBZ";
        }
        return "文件夹";
    }

    private void updateInfoArea(PreviewItem item) {
        // 提取标题行：文本模式 + 标题提取开启且起始标记非空时显示（与正式提取判定一致）
        boolean showTitle = isTextMode() && snapshot.isEnableTitleExtraction()
                && snapshot.getTitleStartMarker() != null && !snapshot.getTitleStartMarker().isEmpty();
        titleInfoLabel.setVisible(showTitle);
        titleInfoLabel.setManaged(showTitle);
        if (item == null) {
            outputNameLabel.setVisible(false);
            outputNameLabel.setManaged(false);
            return;
        }
        if (showTitle) {
            switch (item.status.get()) {
                case WAITING -> titleInfoLabel.setText("提取标题：解析中…");
                case FAILED, EMPTY -> titleInfoLabel.setText("提取标题：—");
                case READY -> titleInfoLabel.setText(item.cachedTitle != null
                        ? "提取标题：" + item.cachedTitle
                        : "提取标题：未提取到标题");
            }
        }
        outputNameLabel.setVisible(true);
        outputNameLabel.setManaged(true);
        if (isTextMode()) {
            if (snapshot.isSeparateFiles()) {
                outputNameLabel.setText("输出文件：" + stripMhtExt(item.name) + ".txt");
            } else {
                String inputDirName = new File(snapshot.getInputFolder()).getName();
                if (inputDirName == null || inputDirName.isEmpty()) {
                    inputDirName = "mht_output";
                }
                outputNameLabel.setText("输出文件：" + inputDirName + ".txt（合并输出，所有文件共用）");
            }
        } else if (snapshot.isSingleOutputMode()) {
            String folderName = mergedFolderName();
            outputNameLabel.setText("输出目录：" + folderName + "（合并输出，所有文件共用）");
        } else {
            String dirName = separateDirName(item);
            outputNameLabel.setText("输出目录：" + dirName);
        }
    }

    private String separateDirName(PreviewItem item) {
        if (snapshot.isEnableNameExtraction()) {
            if (item.status.get() == Status.WAITING) {
                return "解析中…";
            }
            if (item.cachedTitle != null) {
                return SanitizeUtils.sanitize(item.cachedTitle);
            }
        }
        return stripMhtExt(item.name);
    }

    private String mergedFolderName() {
        if (snapshot.isEnableNameExtraction()) {
            PreviewItem first = items.get(0);
            if (first.status.get() == Status.WAITING) {
                return "解析中…";
            }
            if (first.cachedTitle != null) {
                return SanitizeUtils.sanitize(first.cachedTitle);
            }
        }
        return ImageBatchProcessor.determineDefaultFolderName(snapshot.getInputFolder());
    }

    private String stripMhtExt(String name) {
        return name.replaceAll("\\.(?i)(mht|mhtml)$", "");
    }

    // ==================== 内容区渲染 ====================

    private void updateContentArea(PreviewItem item) {
        if (isTextMode()) {
            imagePreviewBox.setVisible(false);
            imagePreviewBox.setManaged(false);
            textPreviewArea.setVisible(true);
            textPreviewArea.setManaged(true);
            if (item == null) {
                textPreviewArea.setText("");
                return;
            }
            switch (item.status.get()) {
                case WAITING -> textPreviewArea.setText("解析中...");
                case FAILED -> textPreviewArea.setText("解析失败：" + item.errorMsg.get());
                case EMPTY -> textPreviewArea.setText("内容为空，提取时该文件将被跳过");
                case READY -> renderTextPreview(item);
            }
        } else {
            textPreviewArea.setVisible(false);
            textPreviewArea.setManaged(false);
            imagePreviewBox.setVisible(true);
            imagePreviewBox.setManaged(true);
            if (item == null) {
                resetImageArea();
                return;
            }
            if (item.status.get() == Status.FAILED) {
                imageHintLabel.setText("解析失败：" + item.errorMsg.get());
                imageHintLabel.setVisible(true);
                previewEffectButton.setVisible(true);
                previewEffectButton.setDisable(true);
                imageScrollPane.setVisible(false);
                imageScrollPane.setManaged(false);
            } else if (item.cachedThumbs != null) {
                renderThumbnails(item);
            } else if (imageLoadActive && loadingItem == item) {
                // 加载中：保留渐进追加的网格，不重置
                imageHintLabel.setText("正在加载...");
                imageHintLabel.setVisible(true);
                previewEffectButton.setVisible(true);
                previewEffectButton.setDisable(true);
                imageScrollPane.setVisible(true);
                imageScrollPane.setManaged(true);
            } else {
                resetImageArea();
            }
        }
    }

    private void renderTextPreview(PreviewItem item) {
        String startTemplate = snapshot.isEnableTemplate() ? snapshot.getStartTemplate() : "";
        String endTemplate = snapshot.isEnableTemplate() ? snapshot.getEndTemplate() : "";
        String head = FileWriter.processTemplate(startTemplate, item.name, item.modifiedTime);
        String tail = FileWriter.processTemplate(endTemplate, item.name, item.modifiedTime);
        String text = item.cachedText == null ? "" : item.cachedText;
        if (text.length() <= 10000) {
            textPreviewArea.setText(head + text + tail);
        } else {
            textPreviewArea.setText(head
                    + text.substring(0, 5000)
                    + "\n……【提取内容】……\n"
                    + text.substring(text.length() - 5000)
                    + tail);
        }
    }

    private void resetImageArea() {
        imageHintLabel.setText("图片预览需要加载整个 MHT 文件，为节省内存开销，采用按需预览。点击'查看预览效果'按钮可查看预览。");
        imageHintLabel.setVisible(true);
        previewEffectButton.setVisible(true);
        previewEffectButton.setDisable(false);
        imageScrollPane.setVisible(false);
        imageScrollPane.setManaged(false);
        thumbnailPane.getChildren().clear();
    }

    private void renderThumbnails(PreviewItem item) {
        List<ThumbEntry> thumbs = item.cachedThumbs;
        thumbnailPane.getChildren().clear();
        if (thumbs == null || thumbs.isEmpty()) {
            imageHintLabel.setText("未提取到图片");
            imageHintLabel.setVisible(true);
            previewEffectButton.setVisible(false);
            imageScrollPane.setVisible(false);
            imageScrollPane.setManaged(false);
            return;
        }
        imageHintLabel.setVisible(false);
        previewEffectButton.setVisible(false);
        imageScrollPane.setVisible(true);
        imageScrollPane.setManaged(true);

        for (ThumbEntry entry : thumbs) {
            thumbnailPane.getChildren().add(buildThumbBox(item, entry));
        }
    }

    private void appendThumb(PreviewItem item, ThumbEntry entry) {
        imageHintLabel.setVisible(false);
        previewEffectButton.setVisible(false);
        imageScrollPane.setVisible(true);
        imageScrollPane.setManaged(true);
        thumbnailPane.getChildren().add(buildThumbBox(item, entry));
    }

    private VBox buildThumbBox(PreviewItem item, ThumbEntry entry) {
        VBox box = new VBox(4);
        box.setAlignment(Pos.TOP_CENTER);
        if (entry.image() == null) {
            box.getChildren().add(new Label("无法加载"));
        } else {
            ImageView iv = new ImageView(entry.image());
            iv.setFitWidth(150);
            iv.setFitHeight(150);
            iv.setPreserveRatio(true);
            box.getChildren().add(iv);
        }
        Label nameLabel = new Label(thumbFileName(entry, items.indexOf(item) + 1));
        nameLabel.setWrapText(true);
        nameLabel.setMaxWidth(150);
        nameLabel.setStyle("-fx-font-size: 0.85em;");
        box.getChildren().add(nameLabel);
        return box;
    }

    /** 合并输出且不重命名时，文件名按当前列表位置重算 f%02d_ 前缀（拖拽重排后同步刷新） */
    private String thumbFileName(ThumbEntry entry, int pos) {
        boolean mergedNoRename = snapshot.isSingleOutputMode()
                && !snapshot.isEnableBatchRenaming() && items.size() > 1;
        return mergedNoRename ? String.format("f%02d_", pos) + entry.coreName() : entry.coreName();
    }

    /** 解码单张缩略图：WebP 走 JWebP 缩放解码（150 边界），其余走 JavaFX 原生缩放解码；失败返回 image=null */
    private ThumbEntry decodeThumb(ImageExtractor.ImageData imageData, int index) {
        byte[] data = imageData.getData();
        String ext = SanitizeUtils.detectExtensionByMagicNumber(data, "jpg");
        boolean rename = snapshot.isEnableBatchRenaming();
        String coreName = ImageWriter.computeFileName(imageData, ext, rename, index + 1, "");
        Image img = null;
        try {
            if ("webp".equals(ext)) {
                WebPImage webp = WebPImage.read(new ByteArrayInputStream(data),
                        WebPImageLoadOptions.builder()
                                .requestedWidth(150).requestedHeight(150)
                                .preserveRatio(true).smooth(true)
                                .build());
                img = new WebPFXImage(webp);
            } else {
                img = new Image(new ByteArrayInputStream(data), 150, 150, true, true);
            }
        } catch (Exception e) {
            // img 保持 null → 占位"无法加载"
        }
        return new ThumbEntry(img, coreName);
    }

    private void updateRenameNote() {
        boolean show = !isTextMode() && snapshot.isSingleOutputMode() && snapshot.isEnableBatchRenaming();
        renameNoteLabel.setVisible(show);
        renameNoteLabel.setManaged(show);
    }

    // ==================== 图片按需加载 ====================

    @FXML
    private void onPreviewEffect() {
        PreviewItem item = fileListView.getSelectionModel().getSelectedItem();
        if (item == null) {
            return;
        }
        if (item.cachedThumbs != null) {
            renderThumbnails(item);
            return;
        }
        if (imageLoadActive && loadingItem == item) {
            return;
        }

        File f = new File(item.path);
        if (f.length() > LARGE_FILE_THRESHOLD) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("大文件预览确认");
            alert.setHeaderText("文件较大，预览可能需要较长时间与较多内存");
            alert.setContentText(item.name + "（" + (f.length() / 1024 / 1024) + " MB），是否继续？");
            alert.getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
            if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                return;
            }
        }

        final int token = ++requestToken;
        imageLoadActive = true;
        loadingItem = item;
        imageHintLabel.setText("正在加载...");
        previewEffectButton.setDisable(true);
        progressBar.setVisible(true);
        progressBar.setProgress(-1);
        statusLabel.setText("正在加载图片：" + item.name);

        Thread t = new Thread(() -> {
            try {
                MimeParser parser = new MimeParser();
                MimeParser.ParsedMht parsed = parser.parse(item.path, false, () -> false, null, null);
                ImageExtractor imageExtractor = new ImageExtractor(parsed,
                        snapshot.getImageNameStartMarker(), snapshot.getImageNameEndMarker(),
                        snapshot.getImageRegionStartMarker(), snapshot.getImageRegionEndMarker());
                List<ImageExtractor.ImageData> images = imageExtractor.extractImages(null);
                if (closed || token != requestToken) {
                    return;
                }

                // 后台逐张解码缩略图，渐进上屏（每张一次 runLater）
                List<ThumbEntry> thumbs = new ArrayList<>();
                for (int i = 0; i < images.size(); i++) {
                    if (closed || token != requestToken) {
                        return;
                    }
                    ThumbEntry entry = decodeThumb(images.get(i), i);
                    thumbs.add(entry);
                    final ThumbEntry e = entry;
                    Platform.runLater(() -> {
                        if (closed || token != requestToken
                                || fileListView.getSelectionModel().getSelectedItem() != item) {
                            return;
                        }
                        appendThumb(item, e);
                    });
                }

                Platform.runLater(() -> {
                    if (closed || token != requestToken
                            || fileListView.getSelectionModel().getSelectedItem() != item) {
                        return;
                    }
                    imageLoadActive = false;
                    loadingItem = null;
                    item.cachedThumbs = thumbs;
                    item.cachedImages = null;
                    if (images.isEmpty()) {
                        // 全量解析确认无图片 → 空内容标记
                        item.status.set(Status.EMPTY);
                        fileListView.refresh();
                    }
                    renderThumbnails(item);
                    updateProgressStatus();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    if (closed || token != requestToken
                            || fileListView.getSelectionModel().getSelectedItem() != item) {
                        return;
                    }
                    imageLoadActive = false;
                    loadingItem = null;
                    imageHintLabel.setText("解析失败：" + e.getMessage());
                    imageHintLabel.setVisible(true);
                    previewEffectButton.setDisable(false);
                    updateProgressStatus();
                });
            }
        }, "preview-image-load");
        t.setDaemon(true);
        t.start();
    }

    // ==================== HTML 轻量预解析 ====================

    private void startPreParse() {
        progressBar.setVisible(true);
        progressBar.setProgress(0);
        Thread t = new Thread(() -> {
            MimeParser parser = new MimeParser();
            for (PreviewItem item : items) {
                if (closed) {
                    return;
                }
                try {
                    MimeParser.ParsedMht parsed = parser.parse(item.path, true, () -> false, null, null);
                    if (parsed.getHtmlContent() == null) {
                        setItemStatus(item, Status.FAILED, "无法解析文件（HTML 内容缺失）");
                        continue;
                    }
                    if (isTextMode()) {
                        TextExtractor textExtractor = new TextExtractor(parsed);
                        String text = textExtractor.extractText(snapshot.isAutoFixEncoding(),
                                snapshot.getStartMarker(), snapshot.getEndMarker(), snapshot.isUseRegex());
                        item.cachedText = text;
                        // 标题缓存：与正式提取共用同一 extractTitle 调用（从完整 HTML 提取）
                        if (snapshot.isEnableTitleExtraction()
                                && snapshot.getTitleStartMarker() != null
                                && !snapshot.getTitleStartMarker().isEmpty()) {
                            try {
                                String title = textExtractor.extractTitle(snapshot.getTitleStartMarker(),
                                        snapshot.getTitleEndMarker(), snapshot.isTitleUseRegex());
                                item.cachedTitle = (title != null && !title.isEmpty()) ? title : null;
                            } catch (Exception e) {
                                item.cachedTitle = null;
                            }
                        }
                        setItemStatus(item,
                                (text == null || text.trim().isEmpty()) ? Status.EMPTY : Status.READY,
                                null);
                    } else {
                        if (snapshot.isEnableNameExtraction()) {
                            ImageExtractor imageExtractor = new ImageExtractor(parsed,
                                    snapshot.getImageNameStartMarker(), snapshot.getImageNameEndMarker(),
                                    null, null);
                            String title = imageExtractor.extractTitle();
                            item.cachedTitle = (title != null && !title.isEmpty()) ? title : null;
                        }
                        setItemStatus(item, Status.READY, null);
                    }
                } catch (Exception e) {
                    setItemStatus(item, Status.FAILED, "解析失败：" + e.getMessage());
                }
            }
        }, "preview-preparse");
        t.setDaemon(true);
        t.start();
    }

    private void setItemStatus(PreviewItem item, Status st, String err) {
        Platform.runLater(() -> {
            if (closed) {
                return;
            }
            item.status.set(st);
            item.errorMsg.set(err == null ? "" : err);
            if (!dragActive) {
                // 拖拽动画期间暂停列表刷新，避免打乱快照画面；拖拽结束后统一重算
                fileListView.refresh();
                recomputeBadges();
            }
            PreviewItem sel = fileListView.getSelectionModel().getSelectedItem();
            updateInfoArea(sel);
            if (sel == item) {
                updateContentArea(item);
            }
            updateProgressStatus();
        });
    }

    private void updateProgressStatus() {
        long done = items.stream().filter(i -> i.status.get() != Status.WAITING).count();
        if (imageLoadActive) {
            progressBar.setVisible(true);
            progressBar.setProgress(-1);
            return;
        }
        if (done >= items.size()) {
            progressBar.setVisible(false);
        } else {
            progressBar.setVisible(true);
            progressBar.setProgress(items.isEmpty() ? 1.0 : (double) done / items.size());
        }
        statusLabel.setText("解析完成 " + done + "/" + items.size());
    }

    // ==================== 提取 / 取消 ====================

    @FXML
    private void onExtract() {
        confirmed = true;
        stage.close();
    }

    @FXML
    private void onCancel() {
        stage.close();
    }

    // ==================== 结果收集 ====================

    public boolean isConfirmed() {
        return confirmed;
    }

    public List<String> getOrderedPaths() {
        return items.stream().map(i -> i.path).toList();
    }

    public Map<String, PerFileOverrides> getOverrides() {
        Map<String, PerFileOverrides> map = new LinkedHashMap<>();
        for (PreviewItem item : items) {
            map.put(item.path, new PerFileOverrides(item.chapterEnabled.get()));
        }
        return map;
    }

    private boolean isTextMode() {
        return !snapshot.isImageModeEnabled();
    }
}
